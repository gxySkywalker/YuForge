package com.yuforge.memory;

import com.yuforge.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConversationHistoryCompactorTest {

    @Test
    void doesNothingWhenBelowTrigger() {
        StubCompactor c = new StubCompactor("MOCK SUMMARY", 3);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        history.add(LlmClient.Message.user("hi"));

        boolean compacted = c.compactIfNeeded(history, 100_000);

        assertFalse(compacted);
        assertEquals(2, history.size());
        assertEquals(0, c.summarizeCalls.get());
    }

    @Test
    void compactNowIgnoresTriggerAndKeepsRecentTurns() {
        StubCompactor c = new StubCompactor("MANUAL SUMMARY", 2);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("SYSTEM_PROMPT"));
        for (int i = 0; i < 3; i++) {
            history.add(LlmClient.Message.user("Q" + i));
            history.add(LlmClient.Message.assistant("A" + i));
        }

        boolean compacted = c.compactNow(history);

        assertTrue(compacted);
        assertEquals(1, c.summarizeCalls.get());
        assertTrue(history.get(1).content().contains("MANUAL SUMMARY"));
        assertEquals(5, history.size());
        assertTrue(history.get(3).content().startsWith("Q2"));
    }

    @Test
    void doesNothingWhenUserTurnsTooFew() {
        // 只有 2 个 user message，retainRecent=3，不应该压缩（即使 token 超阈值）
        StubCompactor c = new StubCompactor("MOCK SUMMARY", 3);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        history.add(LlmClient.Message.user(longText(20_000)));
        history.add(LlmClient.Message.assistant(longText(20_000)));
        history.add(LlmClient.Message.user(longText(20_000)));
        history.add(LlmClient.Message.assistant(longText(20_000)));

        boolean compacted = c.compactIfNeeded(history, 100);

        assertFalse(compacted, "user turns 不够 retainRecent 时应跳过");
        assertEquals(5, history.size());
        assertEquals(0, c.summarizeCalls.get());
    }

    @Test
    void compactsOldRoundsAndKeepsRecentTurns() {
        StubCompactor c = new StubCompactor("MOCK SUMMARY OF OLD CONTENT", 2);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("SYSTEM_PROMPT"));
        // 6 轮 user/assistant
        for (int i = 0; i < 6; i++) {
            history.add(LlmClient.Message.user("Q" + i + ": " + longText(5_000)));
            history.add(LlmClient.Message.assistant("A" + i + ": " + longText(5_000)));
        }

        boolean compacted = c.compactIfNeeded(history, 100);

        assertTrue(compacted);
        assertEquals(1, c.summarizeCalls.get());

        // 重建后结构：[system] + [user(摘要)] + [assistant(确认)] + [保留的 retainRecent=2 个 user 起算的尾部]
        // 即 1 + 1 + 1 + (2 个 user × 2 行 = 4 条) = 7 条
        assertEquals(7, history.size());
        assertEquals("system", history.get(0).role());
        assertEquals("user", history.get(1).role());
        assertTrue(history.get(1).content().contains("已压缩的历史对话摘要"));
        assertTrue(history.get(1).content().contains("MOCK SUMMARY OF OLD CONTENT"));
        assertEquals("assistant", history.get(2).role());

        // 保留的最后两个 user message 仍是 Q4 / Q5（不是 Q3）
        assertTrue(history.get(3).content().startsWith("Q4"));
        assertTrue(history.get(5).content().startsWith("Q5"));
    }

    @Test
    void preservesToolCallPairAtSplitBoundary() {
        // 故意构造一个尾部带 tool_call/tool_result 的形态，验证不会被切断
        StubCompactor c = new StubCompactor("SUMMARY", 2);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        // 旧轮次（应当被压缩）
        history.add(LlmClient.Message.user("OldQ1: " + longText(3_000)));
        history.add(LlmClient.Message.assistant("OldA1"));
        history.add(LlmClient.Message.user("OldQ2"));
        history.add(LlmClient.Message.assistant("OldA2"));
        // 保留的最近两轮，每轮带 tool_call
        history.add(LlmClient.Message.user("RecentQ1"));
        List<LlmClient.ToolCall> tcs1 = List.of(new LlmClient.ToolCall("c1",
                new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a\"}")));
        history.add(LlmClient.Message.assistant(null, null, tcs1));
        history.add(new LlmClient.Message("tool", "file content", null, null, "c1"));
        history.add(LlmClient.Message.assistant("done1"));
        history.add(LlmClient.Message.user("RecentQ2"));
        history.add(LlmClient.Message.assistant("RecentA2"));

        boolean compacted = c.compactIfNeeded(history, 100);

        assertTrue(compacted);
        // 找到摘要后的第一个 user
        int firstUserAfterSummary = -1;
        for (int i = 0; i < history.size(); i++) {
            if ("user".equals(history.get(i).role())
                    && !history.get(i).content().contains("已压缩的历史对话摘要")) {
                firstUserAfterSummary = i;
                break;
            }
        }
        assertTrue(firstUserAfterSummary > 0);
        // splitIdx 必然落在 user 边界，紧随的 assistant(tool_call) 和 tool 配对应该完整保留
        assertEquals("RecentQ1", history.get(firstUserAfterSummary).content());
        assertEquals("assistant", history.get(firstUserAfterSummary + 1).role());
        assertNotNull(history.get(firstUserAfterSummary + 1).toolCalls());
        assertEquals("tool", history.get(firstUserAfterSummary + 2).role());
    }

    @Test
    void emptySummaryAbortsCompaction() {
        StubCompactor c = new StubCompactor("", 2);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        for (int i = 0; i < 5; i++) {
            history.add(LlmClient.Message.user("Q" + i + " " + longText(2_000)));
            history.add(LlmClient.Message.assistant("A" + i));
        }
        int before = history.size();

        boolean compacted = c.compactIfNeeded(history, 100);

        assertFalse(compacted);
        assertEquals(before, history.size());
    }

    @Test
    void llmFailureDoesNotCorruptHistory() {
        StubCompactor c = new StubCompactor(null, 2) {
            @Override
            protected String summarize(List<LlmClient.Message> messages) throws IOException {
                summarizeCalls.incrementAndGet();
                throw new IOException("LLM unavailable");
            }
        };
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        for (int i = 0; i < 5; i++) {
            history.add(LlmClient.Message.user("Q" + i + " " + longText(2_000)));
            history.add(LlmClient.Message.assistant("A" + i));
        }
        int before = history.size();

        boolean compacted = c.compactIfNeeded(history, 100);

        assertFalse(compacted);
        assertEquals(before, history.size());
    }

    @Test
    void archivesOldLargeToolResultWithoutBreakingProtocol() {
        ToolResultArtifactStore store = new ToolResultArtifactStore();
        ConversationHistoryCompactor compactor = new ConversationHistoryCompactor(null, 3, store);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        history.add(LlmClient.Message.user("inspect"));
        for (int i = 0; i < 4; i++) {
            String callId = "call-" + i;
            history.add(LlmClient.Message.assistant(null, null, List.of(new LlmClient.ToolCall(
                    callId, new LlmClient.ToolCall.Function("read_file", "{\"path\":\"f" + i + "\"}")))));
            history.add(LlmClient.Message.tool(callId, i == 0 ? "IMPORTANT=" + longText(8_000) : "short-" + i));
        }

        ConversationHistoryCompactor.ContextManagementResult result = compactor.manageIfNeeded(history, 1_000);

        assertFalse(result.compacted());
        assertEquals(1, result.archivedToolResults());
        LlmClient.Message archived = history.stream()
                .filter(message -> "tool".equals(message.role()) && "call-0".equals(message.toolCallId()))
                .findFirst().orElseThrow();
        assertTrue(archived.content().contains("[旧工具结果已归档]"));
        String artifactId = archived.content().lines()
                .filter(line -> line.startsWith("artifact_id:"))
                .map(line -> line.substring("artifact_id:".length()).trim())
                .findFirst().orElseThrow();
        assertTrue(store.get(artifactId).orElseThrow().content().startsWith("IMPORTANT="));
    }

    @Test
    void productionSummarizerCoversMiddleOfVeryLargeHistory() {
        RecordingSummaryClient client = new RecordingSummaryClient();
        ConversationHistoryCompactor compactor = new ConversationHistoryCompactor(client, 1);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        history.add(LlmClient.Message.user("BEGIN_MARKER\n" + longText(50_000)
                + "\nMIDDLE_MARKER\n" + longText(50_000) + "\nEND_MARKER"));
        history.add(LlmClient.Message.assistant("done"));
        history.add(LlmClient.Message.user("recent"));

        assertTrue(compactor.compactNow(history));
        String allPrompts = String.join("\n", client.prompts);
        assertTrue(allPrompts.contains("BEGIN_MARKER"));
        assertTrue(allPrompts.contains("MIDDLE_MARKER"));
        assertTrue(allPrompts.contains("END_MARKER"));
        assertTrue(client.prompts.size() >= 3, "large history should require map chunks plus reduce");
    }

    private static String longText(int chars) {
        StringBuilder sb = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) sb.append('x');
        return sb.toString();
    }

    /** 测试用 stub：summarize 返回固定字符串，避免真实 LLM 依赖。 */
    private static class StubCompactor extends ConversationHistoryCompactor {
        final AtomicInteger summarizeCalls = new AtomicInteger();
        private final String mockSummary;

        StubCompactor(String mockSummary, int retainRecent) {
            super(null, retainRecent);
            this.mockSummary = mockSummary;
        }

        @Override
        protected String summarize(List<LlmClient.Message> messages) throws IOException {
            summarizeCalls.incrementAndGet();
            return mockSummary;
        }
    }

    private static final class RecordingSummaryClient implements LlmClient {
        final List<String> prompts = new ArrayList<>();

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            prompts.add(messages.get(1).content());
            return new ChatResponse("assistant",
                    "## 目标与用户约束\ncheckpoint-" + prompts.size(), null, 100, 20);
        }

        @Override public String getModelName() { return "test"; }
        @Override public String getProviderName() { return "test"; }
    }
}
