package com.yuforge.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalOutputSafetyTest {

    @Test
    void directTerminalWritesAndFallbackRenderersAvoidEmoji() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        List<String> violations = new ArrayList<>();

        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                boolean directRenderer = isDirectRenderer(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    boolean directWrite = line.contains("System.out.") || line.contains("System.err.");
                    if ((directRenderer || directWrite) && containsEmoji(line)) {
                        violations.add(sourceRoot.relativize(file) + ":" + (i + 1));
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "裸终端输出或 fallback renderer 不得包含易显示为问号的 emoji: " + violations);
    }

    private static boolean isDirectRenderer(Path file) {
        String normalized = file.toString().replace('\\', '/');
        return normalized.endsWith("/render/PlainRenderer.java")
                || normalized.endsWith("/render/inline/InlineApprovalPrompter.java")
                || normalized.endsWith("/render/inline/InlineDiffRenderer.java")
                || normalized.endsWith("/render/inline/SlashPalette.java")
                || normalized.endsWith("/render/inline/ToolCallRenderer.java")
                || normalized.endsWith("/agent/Agent.java")
                || normalized.endsWith("/agent/PlanExecuteAgent.java")
                || normalized.endsWith("/agent/SubAgent.java")
                || normalized.endsWith("/agent/ToolResultSummary.java")
                || normalized.endsWith("/agent/ChangeVerificationTracker.java")
                || normalized.endsWith("/lsp/LspDiagnosticFormatter.java")
                || normalized.contains("/hitl/")
                || normalized.contains("/tui/");
    }

    private static boolean containsEmoji(String text) {
        return text.codePoints().anyMatch(codePoint ->
                (codePoint >= 0x1F000 && codePoint <= 0x1FAFF)
                        || (codePoint >= 0x2600 && codePoint <= 0x27BF)
                        || codePoint == 0xFE0F || codePoint == 0x200D);
    }
}
