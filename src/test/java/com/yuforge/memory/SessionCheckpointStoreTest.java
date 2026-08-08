package com.yuforge.memory;

import com.yuforge.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionCheckpointStoreTest {
    @TempDir Path tempDir;

    @Test
    void savesRecoverableTextHistoryButNotSystemPromptOrArtifactPayload() throws Exception {
        SessionCheckpointStore store = new SessionCheckpointStore(tempDir);
        List<LlmClient.Message> history = List.of(
                LlmClient.Message.system("old system prompt"),
                LlmClient.Message.user("实现登录"),
                LlmClient.Message.tool("call_1", "[旧工具结果已归档]\nartifact_id: tr_123\nsecret source"));

        SessionCheckpointStore.Checkpoint saved = store.save("project-a", "test-model", history, "[]");
        SessionCheckpointStore.Checkpoint loaded = store.load(saved.id());

        assertEquals(2, loaded.messages().size());
        assertEquals("user", loaded.messages().get(0).role());
        assertTrue(loaded.messages().get(1).content().contains("未持久化工具结果原文"));
        assertFalse(loaded.messages().get(1).content().contains("secret source"));
        assertEquals(saved.id(), store.list(10).get(0).id());
    }
}
