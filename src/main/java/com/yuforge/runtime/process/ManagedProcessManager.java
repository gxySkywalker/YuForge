package com.yuforge.runtime.process;

import com.yuforge.policy.CommandGuard;
import com.yuforge.policy.PolicyException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 当前 YuForge 会话启动的开发进程的受控生命周期。
 *
 * <p>该类刻意不支持 shell 的 detached/background 语法。服务进程保持为 YuForge 的子进程，
 * 这样才能可靠地记录日志、展示状态，并在用户停止服务或 CLI 退出时清理进程树。</p>
 */
public final class ManagedProcessManager implements AutoCloseable {
    private static final int DEFAULT_LOG_TAIL_CHARS = 12_000;
    private static final int MAX_LOG_TAIL_CHARS = 48_000;
    private static final int MAX_RETAINED_PROCESSES = 20;
    private static final Pattern URL_PATTERN = Pattern.compile("https?://(?:localhost|127\\.0\\.0\\.1|\\[::1\\])(?::\\d+)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PORT_PATTERN = Pattern.compile("(?:Tomcat|Jetty|Netty).*?(?:port|Port)\\s+(\\d+)|port\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern READY_PATTERN = Pattern.compile(
            "(?:Started .+? in \\d|Tomcat started on port|VITE v\\d|Local:\\s*https?://|ready in \\d|compiled successfully|listening on)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FAILURE_PATTERN = Pattern.compile(
            "(?:APPLICATION FAILED TO START|BUILD FAILURE|Compilation failure|Address already in use|EADDRINUSE|Failed to start|process exited with)",
            Pattern.CASE_INSENSITIVE);

    private final Path workspace;
    private final Path logDirectory;
    private final Map<String, ManagedProcess> processes = new ConcurrentHashMap<>();

    public ManagedProcessManager(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.logDirectory = this.workspace.resolve(".yuforge").resolve("processes");
    }

    public ProcessInfo start(String command) {
        String normalized = command == null ? "" : command.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("命令不能为空");
        }
        String denyReason = CommandGuard.check(normalized);
        if (denyReason != null) {
            throw new PolicyException(denyReason);
        }
        try {
            Files.createDirectories(logDirectory);
            String id = "proc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            Path logFile = logDirectory.resolve(id + ".log");
            ProcessBuilder builder = isWindows()
                    ? new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                    "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(); " + normalized)
                    : new ProcessBuilder("bash", "-lc", normalized);
            builder.directory(workspace.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            Process process = builder.start();
            ManagedProcess managed = new ManagedProcess(id, normalized, logFile, Instant.now(), process);
            processes.put(id, managed);
            trimExitedProcesses();
            return toInfo(managed);
        } catch (IOException e) {
            throw new IllegalStateException("启动后台进程失败: " + e.getMessage(), e);
        }
    }

    public List<ProcessInfo> list() {
        return processes.values().stream()
                .map(this::toInfo)
                .sorted(Comparator.comparing(ProcessInfo::startedAt))
                .toList();
    }

    public String tail(String id, Integer requestedChars) {
        ManagedProcess process = require(id);
        int maxChars = requestedChars == null ? DEFAULT_LOG_TAIL_CHARS
                : Math.max(1, Math.min(requestedChars, MAX_LOG_TAIL_CHARS));
        try {
            if (!Files.exists(process.logFile())) {
                return "日志尚未产生";
            }
            long size = Files.size(process.logFile());
            long readBytes = Math.min(size, (long) maxChars * 4L);
            ByteBuffer buffer = ByteBuffer.allocate((int) readBytes);
            try (SeekableByteChannel channel = Files.newByteChannel(process.logFile(), StandardOpenOption.READ)) {
                channel.position(Math.max(0, size - readBytes));
                while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                    // read to buffer
                }
            }
            String content = StandardCharsets.UTF_8.decode((ByteBuffer) buffer.flip()).toString();
            if (content.length() > maxChars) {
                content = "...(日志尾部已截断)\n" + content.substring(content.length() - maxChars);
            }
            return content.isBlank() ? "日志尚未产生" : content;
        } catch (IOException e) {
            return "读取日志失败: " + e.getMessage();
        }
    }

    public ProcessInfo stop(String id) {
        ManagedProcess managed = require(id);
        stopTree(managed.process());
        return toInfo(managed);
    }

    /** 从日志和子进程状态推断当前服务是否已可供开发使用；不请求网络，避免误把外部连通性当作服务状态。 */
    public ReadinessInfo inspectReadiness(String id) {
        ManagedProcess process = require(id);
        ProcessInfo info = toInfo(process);
        String log = tail(id, MAX_LOG_TAIL_CHARS);
        String endpoint = findEndpoint(log);
        if (FAILURE_PATTERN.matcher(log).find()) {
            return new ReadinessInfo("failed", endpoint, firstRelevantLine(log, FAILURE_PATTERN), info.status(), info.exitCode());
        }
        if (READY_PATTERN.matcher(log).find()) {
            return new ReadinessInfo("ready", endpoint, firstRelevantLine(log, READY_PATTERN), info.status(), info.exitCode());
        }
        if ("exited".equals(info.status())) {
            return new ReadinessInfo("exited", endpoint, "进程已退出；请读取完整日志定位原因", info.status(), info.exitCode());
        }
        return new ReadinessInfo("starting", endpoint, "尚未识别到就绪信号，服务可能仍在构建或初始化", info.status(), info.exitCode());
    }

    public ReadinessInfo waitForReadiness(String id, Integer requestedSeconds) {
        int timeoutSeconds = requestedSeconds == null ? 30 : Math.max(1, Math.min(requestedSeconds, 60));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        ReadinessInfo latest;
        do {
            latest = inspectReadiness(id);
            if (!"starting".equals(latest.status())) {
                return latest;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ReadinessInfo("interrupted", latest.endpoint(), "等待服务就绪已取消", latest.processStatus(), latest.exitCode());
            }
        } while (System.nanoTime() < deadline);
        return new ReadinessInfo("starting", latest.endpoint(), "等待 " + timeoutSeconds + " 秒后仍未识别到就绪信号；请读取日志继续诊断",
                latest.processStatus(), latest.exitCode());
    }

    private ManagedProcess require(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("process_id 不能为空");
        }
        ManagedProcess process = processes.get(id.trim());
        if (process == null) {
            throw new IllegalArgumentException("未找到本会话启动的进程: " + id);
        }
        return process;
    }

    private void trimExitedProcesses() {
        List<ManagedProcess> exited = processes.values().stream()
                .filter(process -> !process.process().isAlive())
                .sorted(Comparator.comparing(ManagedProcess::startedAt))
                .toList();
        int removeCount = Math.max(0, processes.size() - MAX_RETAINED_PROCESSES);
        for (int i = 0; i < removeCount && i < exited.size(); i++) {
            processes.remove(exited.get(i).id());
        }
    }

    private ProcessInfo toInfo(ManagedProcess managed) {
        Process process = managed.process();
        Integer exitCode = null;
        if (!process.isAlive()) {
            try {
                exitCode = process.exitValue();
            } catch (IllegalThreadStateException ignored) {
                // race: process changed state between isAlive and exitValue
            }
        }
        return new ProcessInfo(managed.id(), process.pid(), process.isAlive() ? "running" : "exited",
                exitCode, managed.startedAt(), workspace.relativize(managed.logFile()).toString().replace('\\', '/'),
                abbreviate(managed.command(), 180));
    }

    private void stopTree(Process process) {
        if (!process.isAlive()) {
            return;
        }
        List<ProcessHandle> descendants = process.toHandle().descendants().toList();
        descendants.stream().sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                descendants.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            descendants.forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private static String abbreviate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static String findEndpoint(String log) {
        Matcher url = URL_PATTERN.matcher(log);
        if (url.find()) {
            return url.group();
        }
        Matcher port = PORT_PATTERN.matcher(log);
        if (port.find()) {
            String value = port.group(1) != null ? port.group(1) : port.group(2);
            return value == null ? null : "http://localhost:" + value;
        }
        return null;
    }

    private static String firstRelevantLine(String log, Pattern pattern) {
        for (String line : log.split("\\R")) {
            if (pattern.matcher(line).find()) {
                return abbreviate(line.trim(), 300);
            }
        }
        return "未找到匹配日志行";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @Override
    public void close() {
        new ArrayList<>(processes.values()).forEach(process -> stopTree(process.process()));
    }

    private record ManagedProcess(String id, String command, Path logFile, Instant startedAt, Process process) {
    }

    public record ProcessInfo(String id, long pid, String status, Integer exitCode, Instant startedAt,
                              String logPath, String command) {
    }

    public record ReadinessInfo(String status, String endpoint, String detail, String processStatus, Integer exitCode) {
    }
}
