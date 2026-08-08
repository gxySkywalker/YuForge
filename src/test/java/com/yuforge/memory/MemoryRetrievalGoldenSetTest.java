package com.yuforge.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长期记忆检索的最小 Golden Set。
 *
 * 这些案例不是模型效果评测，而是保护当前关键词检索的产品契约：
 * 相关事实优先、跨项目不泄漏、无有效查询不注入记忆、结果可复现。
 */
class MemoryRetrievalGoldenSetTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldRankMoreRelevantFactBeforePartialMatch() {
        LongTermMemory memory = new LongTermMemory(tempDir.toFile());
        memory.store(entry("partial", "项目使用 Maven", "2026-01-01T00:00:00Z"));
        memory.store(entry("exact", "YuForge 使用 Maven 执行 Java 测试", "2026-02-01T00:00:00Z"));

        List<MemoryEntry> results = memory.search("YuForge Maven 测试", 5, "/repo/current");

        assertEquals(List.of("exact", "partial"), results.stream().map(MemoryEntry::getId).toList());
    }

    @Test
    void shouldNeverReturnCrossProjectFactsOrEntriesForEmptyQuery() {
        LongTermMemory memory = new LongTermMemory(tempDir.toFile());
        memory.store(entry("current", "YuForge 使用 Maven", "2026-01-01T00:00:00Z"));
        memory.store(new MemoryEntry("other", "YuForge 使用 Gradle", MemoryEntry.MemoryType.FACT,
                Instant.parse("2026-02-01T00:00:00Z"),
                Map.of("scope", "project", "project", "/repo/other"), 10));

        List<MemoryEntry> results = memory.search("YuForge", 5, "/repo/current");

        assertEquals(List.of("current"), results.stream().map(MemoryEntry::getId).toList());
        assertTrue(memory.search("", 5, "/repo/current").isEmpty());
    }

    private static MemoryEntry entry(String id, String content, String timestamp) {
        return new MemoryEntry(id, content, MemoryEntry.MemoryType.FACT,
                Instant.parse(timestamp), Map.of("scope", "project", "project", "/repo/current"), 10);
    }
}
