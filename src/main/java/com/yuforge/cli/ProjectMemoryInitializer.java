package com.yuforge.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

final class ProjectMemoryInitializer {
    private ProjectMemoryInitializer() {
    }

    static InitResult initialize(Path projectRoot, boolean force) throws IOException {
        Path root = projectRoot == null ? Path.of(".").toAbsolutePath().normalize()
                : projectRoot.toAbsolutePath().normalize();
        Path target = root.resolve("YUFORGE.md");
        if (Files.exists(target) && !force) {
            return new InitResult(false, target, "YUFORGE.md 已存在；使用 /init --force 可重写。");
        }
        Files.createDirectories(root);
        String content = generate(root);
        Files.writeString(target, content, StandardCharsets.UTF_8);
        return new InitResult(true, target, "已生成项目级记忆 YUFORGE.md。");
    }

    static String generate(Path projectRoot) throws IOException {
        Path root = projectRoot == null ? Path.of(".").toAbsolutePath().normalize()
                : projectRoot.toAbsolutePath().normalize();
        ProjectFacts facts = inspect(root);
        return """
                # YUFORGE.md

                ## Commands

                %s

                ## What This Is

                %s

                ## Architecture

                %s

                ## Things That Will Bite You

                %s

                ## Don't

                %s
                """.formatted(
                bulletList(facts.commands()),
                facts.description(),
                bulletList(facts.architecture()),
                bulletList(facts.pitfalls()),
                bulletList(facts.donts())
        ).trim() + "\n";
    }

    private static ProjectFacts inspect(Path root) throws IOException {
        String readme = readIfExists(root.resolve("README.md"));
        String agents = readIfExists(root.resolve("AGENTS.md"));
        String combined = (readme + "\n" + agents).toLowerCase(Locale.ROOT);
        String name = projectName(root, readme);

        List<String> commands = new ArrayList<>();
        if (Files.exists(root.resolve("pom.xml"))) {
            commands.add("构建：`mvn clean package`；如项目约定默认跳过测试，以 README/AGENTS 为准。");
            commands.add("快速回归：优先运行 README/AGENTS 指定的 Maven profile；没有 profile 时用 `mvn test`。");
            commands.add("针对性测试：`mvn test -Dtest=XxxTest -DskipTests=false`。");
        } else if (Files.exists(root.resolve("package.json"))) {
            commands.add("安装依赖：`npm install` 或项目锁文件对应的包管理器命令。");
            commands.add("构建/测试：优先使用 `package.json` scripts 中已有的 `build` / `test` / `lint`。");
        } else if (Files.exists(root.resolve("Makefile"))) {
            commands.add("优先使用 `make help` 或 Makefile 中已有 target，不要猜测构建命令。");
        } else {
            commands.add("先读取 README/AGENTS 中的构建和测试命令，再执行。");
        }

        String description = "%s 是当前工作区项目；修改前先读代码实际行为，再参考本文件、README 和路线图。".formatted(name);
        List<String> architecture = new ArrayList<>();
        List<String> pitfalls = new ArrayList<>();
        List<String> donts = new ArrayList<>();

        if (combined.contains("yuforge")) {
            description = "YuForge 是面向商业使用的 Java Agent CLI 产品，对标 Claude Code；主路径是 ReAct、Plan-and-Execute、Multi-Agent 三套执行模式。";
            commands = List.of(
                    "构建：`mvn clean package` 默认跳过测试，优先产出可手工验收 jar。",
                    "常规回归：`mvn test -Pquick`；TUI 相关跑 `mvn test -Pphase16-smoke`。",
                    "针对性测试：`mvn test -Dtest=XxxTest -DskipTests=false`。"
            );
            architecture.add("三条执行路径共享 `ToolRegistry` / `MemoryManager` / `SnapshotService`，不要为某个模式创建孤立能力。");
            architecture.add("精确代码定位优先 `glob_files` / `grep_code` / `read_file`，`search_code` 只做 RAG 语义辅助。");
            architecture.add("system prompt 由 `PromptAssembler` 分层组装；内置 prompt 在 `src/main/resources/prompts/`。");
            pitfalls.add("改行为要同步 `AGENTS.md` / `README.md` / `ROADMAP.md`；路线图只在状态变化时更新。");
            pitfalls.add("改命令入口要联动 `Main.java`、`CliCommandParser.java`、测试、`README.md`、`AGENTS.md`。");
            pitfalls.add("改工具集要联动 `ToolRegistry.java`、Agent/Plan/SubAgent 提示词和文档。");
            pitfalls.add("长期记忆只通过 `/save` 或用户明确要求保存；不要自动提取临时事实。");
            donts.add("不提交 `.env`、真实 API Key、`target/` 产物。");
            donts.add("不把 `ROADMAP.md` 的未来计划写成已交付能力。");
            donts.add("不在交互主路径新增裸 `System.out.println`；优先走 `Renderer.stream()`。");
        } else {
            architecture.addAll(inspectArchitecture(root, readme));
            architecture.add("优先沿用上述目录、框架和 helper；不要为局部改动新建无必要抽象。");
            architecture.add("代码定位先用文件名/符号/字符串搜索，再按需读取具体行段。");
            pitfalls.add("README、ROADMAP 或 issue 可能滞后；以代码实际行为和测试为准。");
            pitfalls.add("生成文件、构建产物、密钥和本地配置不要进入版本控制。");
            pitfalls.add("已有未提交改动默认视为用户改动，除非用户明确要求，不要回退。");
            donts.add("不要提交 `.env`、真实 API Key、构建产物或本地 IDE 配置。");
            donts.add("不要把临时任务说明写进 `YUFORGE.md`；这份文件只放长期稳定规则。");
            donts.add("不要写“保持代码整洁”这类无行动指导的空规则。");
        }

        return new ProjectFacts(commands, description, architecture, pitfalls, donts);
    }

    /**
     * 仅做有界的本地目录/配置扫描，不调用模型也不读取业务源码全文。
     * `/init` 的目标是留下可验证的导航，而不是生成看似正确的架构故事。
     */
    private static List<String> inspectArchitecture(Path root, String readme) throws IOException {
        List<String> facts = new ArrayList<>();
        Path javaSource = root.resolve("src/main/java");
        Path resources = root.resolve("src/main/resources");
        if (Files.isDirectory(javaSource)) {
            Path packageRoot = detectJavaPackageRoot(javaSource);
            if (packageRoot != null) {
                String relative = relative(root, packageRoot);
                List<String> modules = childDirectories(packageRoot, 10);
                if (!modules.isEmpty()) {
                    facts.add("主 Java 包：`%s`；模块目录：`%s`。".formatted(relative,
                            String.join("`、`", modules)));
                } else {
                    facts.add("主 Java 包：`%s`。".formatted(relative));
                }
            } else {
                facts.add("Java 源码位于 `src/main/java`。 ");
            }
            findFirst(javaSource, "*Application.java", 6).ifPresent(entry ->
                    facts.add("启动入口：`%s`。".formatted(relative(root, entry))));
            long sourceFiles = countJavaFiles(javaSource, 8);
            long testFiles = countJavaFiles(root.resolve("src/test/java"), 8);
            facts.add("代码规模：`src/main/java` %d 个 Java 文件；`src/test/java` %d 个测试文件。"
                    .formatted(sourceFiles, testFiles));
            if (Files.isDirectory(resources)) {
                List<String> configs = filesMatching(resources, "application", 4);
                if (!configs.isEmpty()) {
                    facts.add("运行配置：`src/main/resources/%s`。".formatted(String.join("`、src/main/resources/`", configs)));
                }
            }
            String pom = readIfExists(root.resolve("pom.xml")).toLowerCase(Locale.ROOT);
            List<String> stack = new ArrayList<>();
            if (pom.contains("spring-boot")) stack.add("Spring Boot");
            if (pom.contains("mybatis")) stack.add("MyBatis");
            if (pom.contains("redis")) stack.add("Redis");
            if (!stack.isEmpty()) facts.add("已声明技术栈：%s（以 `pom.xml` 为准）。".formatted(String.join(" + ", stack)));
        } else if (Files.isRegularFile(root.resolve("package.json"))) {
            List<String> dirs = childDirectories(root.resolve("src"), 10);
            if (!dirs.isEmpty()) facts.add("前端源码目录：`src/%s`。".formatted(String.join("`、`", dirs)));
            facts.add("运行脚本和依赖以 `package.json` 为准。 ");
        } else {
            List<String> dirs = childDirectories(root, 8);
            if (!dirs.isEmpty()) facts.add("主要目录：`%s`。".formatted(String.join("`、`", dirs)));
        }
        return facts;
    }

    private static Path detectJavaPackageRoot(Path sourceRoot) throws IOException {
        List<Path> first = childDirectoryPaths(sourceRoot, 4);
        if (first.size() != 1) return null;
        List<Path> second = childDirectoryPaths(first.get(0), 8);
        if (second.size() == 1 && childDirectoryPaths(second.get(0), 2).size() > 0) return second.get(0);
        return first.get(0);
    }

    private static List<String> childDirectories(Path directory, int limit) throws IOException {
        return childDirectoryPaths(directory, limit).stream().map(path -> path.getFileName().toString()).toList();
    }

    private static List<Path> childDirectoryPaths(Path directory, int limit) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .limit(limit)
                    .toList();
        }
    }

    private static java.util.Optional<Path> findFirst(Path root, String glob, int maxDepth) throws IOException {
        try (Stream<Path> paths = Files.find(root, maxDepth,
                (path, attrs) -> attrs.isRegularFile() && path.getFileName().toString().endsWith("Application.java"))) {
            return paths.sorted().findFirst();
        }
    }

    private static List<String> filesMatching(Path root, String prefix, int limit) throws IOException {
        try (Stream<Path> paths = Files.find(root, 2,
                (path, attrs) -> attrs.isRegularFile() && path.getFileName().toString().startsWith(prefix))) {
            return paths.sorted().limit(limit).map(path -> relative(root, path)).toList();
        }
    }

    private static long countJavaFiles(Path root, int maxDepth) throws IOException {
        if (!Files.isDirectory(root)) return 0L;
        try (Stream<Path> paths = Files.find(root, maxDepth,
                (path, attrs) -> attrs.isRegularFile() && path.getFileName().toString().endsWith(".java"))) {
            return paths.count();
        }
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String projectName(Path root, String readme) {
        for (String line : readme.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
        }
        Path fileName = root.getFileName();
        return fileName == null ? "当前项目" : fileName.toString();
    }

    private static String bulletList(List<String> items) {
        return String.join("\n", items.stream().limit(5).map(item -> "- " + item).toList());
    }

    private static String readIfExists(Path path) throws IOException {
        return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
    }

    record InitResult(boolean written, Path path, String message) {
    }

    private record ProjectFacts(
            List<String> commands,
            String description,
            List<String> architecture,
            List<String> pitfalls,
            List<String> donts
    ) {
    }
}
