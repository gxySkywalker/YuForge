package com.yuforge.memory;

import com.yuforge.llm.GLMClient;
import com.yuforge.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCompressBeforeShortTermMemoryEvictsOldEntries() {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse("assistant", "压缩摘要", null, 100, 20)
        ));
        MemoryManager memoryManager = new MemoryManager(
                llmClient,
                40,
                128000,
                new LongTermMemory(tempDir.toFile())
        );
        String longMessage = "a".repeat(36);

        memoryManager.addUserMessage(longMessage);
        memoryManager.addAssistantMessage(longMessage);
        memoryManager.addUserMessage(longMessage);
        memoryManager.addAssistantMessage(longMessage);

        assertTrue(memoryManager.getShortTermMemory().getAll().stream()
                .anyMatch(entry -> entry.getType() == MemoryEntry.MemoryType.SUMMARY));
    }

    @Test
    void shouldClearLongTermMemoryOnlyWhenExplicitlyRequested() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 32768, 128000, longTermMemory);

        memoryManager.storeFact("用户偏好使用中文交流");
        memoryManager.storeFact("项目路径: /tmp/demo");
        assertEquals(2, longTermMemory.size());

        memoryManager.clearLongTerm();

        assertEquals(0, longTermMemory.size());
    }

    @Test
    void shouldStoreProjectScopedFactsByDefault() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 32768, 128000, longTermMemory);
        memoryManager.setProjectPath("/repo/current");

        memoryManager.storeFact("当前项目使用 Java 17");
        memoryManager.storeFact("默认用中文回答", "global");

        MemoryEntry projectEntry = longTermMemory.search("Java", 5, memoryManager.getCurrentProject()).get(0);
        assertEquals("project", projectEntry.getMetadata().get("scope"));
        assertEquals(memoryManager.getCurrentProject(), projectEntry.getMetadata().get("project"));
        assertEquals("global", longTermMemory.search("中文", 5).get(0).getMetadata().get("scope"));
    }

    @Test
    void shouldKeepProjectMemoryOperationsWithinVisibleScope() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 32768, 128000, longTermMemory);
        memoryManager.setProjectPath("/repo/current");
        longTermMemory.store(new MemoryEntry("current", "当前项目", MemoryEntry.MemoryType.FACT,
                java.util.Map.of("scope", "project", "project", memoryManager.getCurrentProject()), 10));
        longTermMemory.store(new MemoryEntry("other", "其他项目", MemoryEntry.MemoryType.FACT,
                java.util.Map.of("scope", "project", "project", "/repo/other"), 10));
        longTermMemory.store(new MemoryEntry("global", "全局偏好", MemoryEntry.MemoryType.FACT,
                java.util.Map.of("scope", "global"), 10));

        assertEquals(2, memoryManager.listLongTerm().size());
        assertFalse(memoryManager.deleteLongTerm("other"));
        memoryManager.clearLongTerm();

        assertFalse(longTermMemory.retrieve("current").isPresent());
        assertTrue(longTermMemory.retrieve("other").isPresent());
        assertTrue(longTermMemory.retrieve("global").isPresent());
    }

    @Test
    void shouldRetrieveLongTermMemoryInDeterministicOrder() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 32768, 128000, longTermMemory);
        memoryManager.setProjectPath("/repo/current");
        longTermMemory.store(new MemoryEntry("b", "Java 构建命令", MemoryEntry.MemoryType.FACT,
                java.time.Instant.parse("2026-01-01T00:00:00Z"),
                java.util.Map.of("scope", "project", "project", memoryManager.getCurrentProject()), 10));
        longTermMemory.store(new MemoryEntry("a", "Java 测试命令", MemoryEntry.MemoryType.FACT,
                java.time.Instant.parse("2026-02-01T00:00:00Z"),
                java.util.Map.of("scope", "project", "project", memoryManager.getCurrentProject()), 10));

        List<MemoryEntry> results = memoryManager.searchLongTerm("Java", 10);

        assertEquals(List.of("a", "b"), results.stream().map(MemoryEntry::getId).toList());
    }

    @Test
    void shouldSearchOnlyCurrentProjectAndGlobalFacts() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 32768, 128000, longTermMemory);
        memoryManager.setProjectPath("/repo/current");
        longTermMemory.store(new MemoryEntry("current", "当前项目使用 Java 17", MemoryEntry.MemoryType.FACT,
                java.util.Map.of("scope", "project", "project", memoryManager.getCurrentProject()), 10));
        longTermMemory.store(new MemoryEntry("other", "其他项目使用 Java 8", MemoryEntry.MemoryType.FACT,
                java.util.Map.of("scope", "project", "project", "/repo/other"), 10));

        List<MemoryEntry> results = memoryManager.searchLongTerm("Java", 10);

        assertEquals(1, results.size());
        assertEquals("current", results.get(0).getId());
    }

    @Test
    void compressionTriggerRatioAppliesToAllModelsUniformly() {
        // 验证：长 window 模型也使用自动压缩阈值，没有"长模式不压缩"的二元开关
        MemoryManager memoryManager = new MemoryManager(new GLMClient("test-key"));

        assertEquals(0.80, memoryManager.getContextProfile().compressionTriggerRatio(), 0.001);
        assertEquals(200000, memoryManager.getTokenBudget().getContextWindow());
        assertEquals(160000, memoryManager.getContextProfile().compressionTriggerTokens());
    }

    private static final class StubGLMClient extends GLMClient {
        private final Queue<ChatResponse> responses;

        private StubGLMClient(List<ChatResponse> responses) {
            super("test-key");
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }
    }
}
