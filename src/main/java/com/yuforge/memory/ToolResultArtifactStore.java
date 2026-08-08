package com.yuforge.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 会话级工具结果归档。
 *
 * <p>上下文治理可以把旧的大型 tool_result 替换为短占位符，同时保留一个可审计、可恢复的
 * artifact id。存储有条目数和总字符数双重上限，避免长会话把 JVM 内存无限撑大。</p>
 */
public final class ToolResultArtifactStore {
    private static final int DEFAULT_MAX_ENTRIES = 256;
    private static final int DEFAULT_MAX_CHARS = 16 * 1024 * 1024;

    private final int maxEntries;
    private final int maxChars;
    private final LinkedHashMap<String, Artifact> artifacts = new LinkedHashMap<>();
    private int currentChars;

    public ToolResultArtifactStore() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_CHARS);
    }

    ToolResultArtifactStore(int maxEntries, int maxChars) {
        this.maxEntries = Math.max(1, maxEntries);
        this.maxChars = Math.max(1_024, maxChars);
    }

    public synchronized Artifact archive(String toolName, String toolCallId, String content) {
        String normalizedContent = content == null ? "" : content;
        String id = artifactId(toolName, toolCallId, normalizedContent);
        Artifact existing = artifacts.get(id);
        if (existing != null) {
            return existing;
        }
        Artifact artifact = new Artifact(
                id,
                blankToUnknown(toolName),
                blankToUnknown(toolCallId),
                normalizedContent,
                TokenBudget.estimateTextTokens(normalizedContent),
                Instant.now()
        );
        artifacts.put(id, artifact);
        currentChars += normalizedContent.length();
        evictIfNeeded();
        return artifact;
    }

    public synchronized Optional<Artifact> get(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(artifacts.get(id.trim()));
    }

    public synchronized int size() {
        return artifacts.size();
    }

    public synchronized void clear() {
        artifacts.clear();
        currentChars = 0;
    }

    private void evictIfNeeded() {
        while ((artifacts.size() > maxEntries || currentChars > maxChars) && artifacts.size() > 1) {
            Map.Entry<String, Artifact> oldest = artifacts.entrySet().iterator().next();
            currentChars -= oldest.getValue().content().length();
            artifacts.remove(oldest.getKey());
        }
    }

    private static String artifactId(String toolName, String toolCallId, String content) {
        String source = blankToUnknown(toolName) + "\n" + blankToUnknown(toolCallId) + "\n" + content;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return "tr_" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    public record Artifact(
            String id,
            String toolName,
            String toolCallId,
            String content,
            int tokenCount,
            Instant createdAt
    ) {
        public String formatForTool() {
            return "[已恢复工具结果]\n"
                    + "artifact_id: " + id + "\n"
                    + "tool: " + toolName + "\n"
                    + "tool_call_id: " + toolCallId + "\n"
                    + "original_tokens: " + tokenCount + "\n\n"
                    + content;
        }
    }
}
