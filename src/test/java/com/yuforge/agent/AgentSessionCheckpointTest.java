package com.yuforge.agent;

import com.yuforge.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSessionCheckpointTest {
    @TempDir Path tempDir;

    @Test
    void restoresSameProjectHistoryWithFreshSystemPrompt() {
        String oldDir = System.getProperty("yuforge.session.dir");
        System.setProperty("yuforge.session.dir", tempDir.toString());
        try {
            Agent agent = new Agent(new SingleResponseClient());
            agent.getToolRegistry().setProjectPath(tempDir.toString());
            agent.getMemoryManager().setProjectPath(tempDir.toString());
            agent.run("记住当前任务上下文");

            Agent.SessionCheckpointResult saved = agent.saveSessionCheckpoint();
            agent.clearHistory();
            Agent.SessionCheckpointResult restored = agent.resumeSessionCheckpoint(saved.sessionId());

            assertTrue(saved.succeeded());
            assertTrue(restored.succeeded());
            assertEquals("system", agent.getConversationHistory().get(0).role());
            assertTrue(agent.getConversationHistory().stream().anyMatch(message ->
                    "user".equals(message.role()) && message.content().contains("记住当前任务上下文")));
        } finally {
            if (oldDir == null) System.clearProperty("yuforge.session.dir");
            else System.setProperty("yuforge.session.dir", oldDir);
        }
    }

    private static final class SingleResponseClient implements LlmClient {
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return new ChatResponse("assistant", "已完成", null, 10, 5);
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            return chat(messages, tools);
        }
        @Override public String getModelName() { return "test"; }
        @Override public String getProviderName() { return "test"; }
        @Override public int maxContextWindow() { return 128_000; }
    }
}
