package com.yuforge.cli;

import com.yuforge.config.YuForgeConfig;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** 只读环境自检，刻意不执行 Maven/Git/Node，避免 /doctor 改变工作区或产生网络请求。 */
final class EnvironmentDoctor {
    private EnvironmentDoctor() {
    }

    static String report(Path workspace, YuForgeConfig config, String provider, String model, String mcpSummary) {
        Path root = workspace.toAbsolutePath().normalize();
        boolean hasPom = Files.isRegularFile(root.resolve("pom.xml"));
        boolean hasPackageJson = Files.isRegularFile(root.resolve("package.json"));
        String normalizedProvider = provider == null || provider.isBlank() ? "unknown" : provider;
        String apiKey = config == null ? null : config.getApiKey(normalizedProvider);

        StringBuilder out = new StringBuilder("🩺 YuForge 环境诊断（只读）\n");
        out.append(item(Files.isDirectory(root), "工作区", root.toString(), "当前目录不可用或不存在"));
        out.append(item(Files.isWritable(root), "工作区写入", "可写（仍受 HITL / PathGuard 约束）", "不可写：修改、构建输出和 Side-Git 快照可能失败"));
        out.append(item(isJava17OrLater(), "Java", "Java " + System.getProperty("java.version", "unknown"), "需要 Java 17+"));
        out.append(item(commandAvailable("git"), "Git", "可用", "未在 PATH 中发现；快照与常规 Git 工作流会受限"));
        out.append(item(commandAvailable("rg"), "ripgrep", "可用", "未发现：grep_code 会自动回退 Java 扫描，速度较慢"));
        if (hasPom) {
            out.append(item(commandAvailable("mvn") || commandAvailable("mvn.cmd"), "Maven", "检测到 pom.xml 且 Maven 可用", "检测到 pom.xml，但未在 PATH 中发现 Maven"));
        } else {
            out.append("• Maven: 不适用（当前目录没有 pom.xml）\n");
        }
        if (hasPackageJson) {
            boolean node = commandAvailable("node");
            boolean npm = commandAvailable("npm") || commandAvailable("npm.cmd");
            out.append(item(node && npm, "Node/npm", "检测到 package.json 且 Node/npm 可用", "检测到 package.json，但 Node 或 npm 未在 PATH 中"));
        } else {
            out.append("• Node/npm: 不适用（当前目录没有 package.json）\n");
        }
        out.append(item(apiKey != null && !apiKey.isBlank(), "当前模型 API Key", normalizedProvider + " 已配置（不显示密钥）",
                normalizedProvider + " 未配置；使用 /config provider " + normalizedProvider + " --api-key <key> 或项目 .env"));
        out.append("• 当前模型: ").append(model == null || model.isBlank() ? "未知" : model)
                .append(" (").append(normalizedProvider).append(")\n");
        out.append("• MCP: ").append(mcpSummary == null || mcpSummary.isBlank() ? "未加载" : mcpSummary).append("\n");
        out.append("\n提示：/doctor 只检查本机可见配置与 PATH，不验证 API 连通性、不安装依赖，也不运行项目。\n");
        return out.toString();
    }

    private static String item(boolean ok, String name, String success, String failure) {
        return (ok ? "✓ " : "! ") + name + ": " + (ok ? success : failure) + '\n';
    }

    private static boolean isJava17OrLater() {
        String specification = System.getProperty("java.specification.version", "");
        try {
            return Integer.parseInt(specification.replace("1.", "")) >= 17;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean commandAvailable(String command) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String[] extensions = windows ? new String[]{"", ".exe", ".cmd", ".bat"} : new String[]{""};
        for (String entry : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            for (String extension : extensions) {
                Path candidate = Path.of(entry).resolve(command + extension);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }
}
