package com.yuforge.agent;

import com.yuforge.llm.LlmClient;
import com.yuforge.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void injectsRuntimeContextInUserTurnAndEscalatesRepeatedToolFailure() {
        LlmClient.ToolCall firstCall = call("call_1");
        LlmClient.ToolCall secondCall = call("call_2");
        RecordingClient client = new RecordingClient(List.of(
                new LlmClient.ChatResponse("assistant", "", List.of(firstCall), 20, 5),
                new LlmClient.ChatResponse("assistant", "", List.of(secondCall), 20, 5),
                new LlmClient.ChatResponse("assistant", "已改用其他策略。", null, 20, 5)
        ));
        ToolRegistry registry = new ToolRegistry() {
            @Override
            public String executeTool(String name, String argumentsJson) {
                return "读取文件失败: 文件不存在";
            }
        };
        registry.setProjectPath(tempDir.toString());

        Agent agent = new Agent(client, registry);
        agent.run("读取 missing.txt");

        String system = client.calls.get(0).get(0).content();
        String firstUser = client.calls.get(0).stream()
                .filter(message -> "user".equals(message.role()))
                .findFirst().orElseThrow().content();
        String secondFailure = client.calls.get(2).stream()
                .filter(message -> "tool".equals(message.role()))
                .reduce((previous, current) -> current)
                .orElseThrow().content();

        assertFalse(system.contains("## Runtime Context"));
        assertFalse(system.contains("<timestamp>"));
        assertTrue(firstUser.contains("<environment_context>"));
        assertTrue(firstUser.contains("<workspace>" + tempDir.toAbsolutePath().normalize() + "</workspace>"));
        assertTrue(firstUser.contains("<user_request>\n读取 missing.txt\n</user_request>"));
        assertTrue(secondFailure.contains("\"same_error_attempt\" : 2"));
        assertTrue(secondFailure.contains("不要使用相同参数重试"));
    }

    private static LlmClient.ToolCall call(String id) {
        return new LlmClient.ToolCall(id,
                new LlmClient.ToolCall.Function("read_file", "{\"path\":\"missing.txt\"}"));
    }

    private static final class RecordingClient implements LlmClient {
        private final Queue<ChatResponse> responses;
        private final List<List<Message>> calls = new ArrayList<>();

        private RecordingClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            calls.add(List.copyOf(messages));
            ChatResponse response = responses.poll();
            if (response == null) throw new IOException("缺少预设响应");
            return response;
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        public int maxContextWindow() {
            return 256_000;
        }
    }
}
