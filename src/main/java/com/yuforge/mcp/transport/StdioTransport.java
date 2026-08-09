package com.yuforge.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class StdioTransport implements McpTransport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_STDERR_LINES = 200;

    private final Process process;
    private final BufferedWriter stdin;
    private final List<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<String> stderrRing = new ArrayDeque<>();
    private final Object stderrLock = new Object();
    private volatile boolean closed;

    public StdioTransport(String command, List<String> args, Map<String, String> env, Path workingDir) throws IOException {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(resolveCommandForProcess(command));
        if (args != null) {
            commandLine.addAll(args);
        }
        ProcessBuilder builder = new ProcessBuilder(commandLine);
        if (workingDir != null) {
            builder.directory(workingDir.toFile());
        }
        if (env != null && !env.isEmpty()) {
            builder.environment().putAll(env);
        }
        this.process = builder.start();
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        startStdoutReader();
        startStderrReader();
    }

    /**
     * Windows 的 npm launcher 通常是 {@code npx.cmd} / {@code npm.cmd}。
     * PowerShell 会依赖 PATHEXT 自动找到它们，但 Java ProcessBuilder 在部分
     * 环境只尝试裸命令，导致 shell 中可用的 npx 在 MCP 子进程中报 CreateProcess error=2。
     */
    static String resolveCommandForProcess(String command) {
        return resolveCommandForProcess(command, isWindows(), System.getenv("PATH"));
    }

    static String resolveCommandForProcess(String command, boolean windows, String path) {
        if (command == null || command.isBlank() || !windows || hasFileExtension(command)
                || path == null || path.isBlank()) {
            return command;
        }
        for (String entry : path.split(";")) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry.trim()).resolve(command + ".cmd");
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return command;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static boolean hasFileExtension(String command) {
        String fileName = Path.of(command).getFileName().toString();
        return fileName.lastIndexOf('.') > 0;
    }

    @Override
    public synchronized void send(JsonNode message) throws IOException {
        if (closed) {
            throw new IOException("MCP stdio transport already closed");
        }
        stdin.write(MAPPER.writeValueAsString(message));
        stdin.newLine();
        stdin.flush();
    }

    @Override
    public void onReceive(Consumer<JsonNode> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public List<String> stderrLines() {
        synchronized (stderrLock) {
            return List.copyOf(stderrRing);
        }
    }

    @Override
    public Long processId() {
        return process.pid();
    }

    @Override
    public String transportName() {
        return "stdio";
    }

    @Override
    public void close() {
        closed = true;
        // 关 stdin 让子进程读到 EOF，给一次优雅退出窗口（1 秒）。
        // shutdown 通知由 McpClient.close 在调本方法之前发出，子进程拿到 EOF 后通常会立即退出。
        try {
            stdin.close();
        } catch (IOException ignored) {
        }
        try {
            if (process.waitFor(1, TimeUnit.SECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return;
        }
        // 优雅窗口超时，发 SIGTERM
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void startStdoutReader() {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode message = MAPPER.readTree(line);
                    for (Consumer<JsonNode> listener : listeners) {
                        listener.accept(message);
                    }
                }
            } catch (Exception e) {
                appendStderr("[yuforge] stdout reader stopped: " + e.getMessage());
            }
        }, "yuforge-mcp-stdio-stdout");
        thread.setDaemon(true);
        thread.start();
    }

    private void startStderrReader() {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendStderr(line);
                }
            } catch (IOException e) {
                appendStderr("[yuforge] stderr reader stopped: " + e.getMessage());
            }
        }, "yuforge-mcp-stdio-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    private void appendStderr(String line) {
        synchronized (stderrLock) {
            while (stderrRing.size() >= MAX_STDERR_LINES) {
                stderrRing.removeFirst();
            }
            stderrRing.addLast(line);
        }
    }
}
