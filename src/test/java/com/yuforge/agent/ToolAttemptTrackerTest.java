package com.yuforge.agent;

import com.yuforge.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolAttemptTrackerTest {

    @Test
    void countsCanonicalSignatureAndEscalatesRepeatedFailure() {
        ToolRegistry registry = failingRegistry();
        ToolAttemptTracker tracker = new ToolAttemptTracker();

        ToolRegistry.ToolExecutionResult first = execute(registry, "{\"path\":\"missing.txt\",\"max\":10}");
        ToolRegistry.ToolExecutionResult second = execute(registry, "{\"max\":10,\"path\":\"missing.txt\"}");

        ToolAttemptTracker.Observation firstObservation = tracker.observe(first);
        ToolAttemptTracker.Observation secondObservation = tracker.observe(second);

        assertEquals(1, firstObservation.attempt());
        assertEquals(2, secondObservation.attempt(), "JSON 字段顺序不应改变调用签名");
        assertEquals(2, secondObservation.sameErrorAttempt());
        assertTrue(secondObservation.modelResult().contains("\"code\" : \"FILE_NOT_FOUND\""));
        assertTrue(secondObservation.modelResult().contains("不要使用相同参数重试"));
    }

    @Test
    void differentArgumentsHaveIndependentCounters() {
        ToolRegistry registry = failingRegistry();
        ToolAttemptTracker tracker = new ToolAttemptTracker();

        tracker.observe(execute(registry, "{\"path\":\"a.txt\"}"));
        ToolAttemptTracker.Observation other = tracker.observe(execute(registry, "{\"path\":\"b.txt\"}"));

        assertEquals(1, other.attempt());
    }

    private static ToolRegistry failingRegistry() {
        return new ToolRegistry() {
            @Override
            public String executeTool(String name, String argumentsJson) {
                return "读取文件失败: 文件不存在";
            }
        };
    }

    private static ToolRegistry.ToolExecutionResult execute(ToolRegistry registry, String args) {
        return registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call", "read_file", args))).get(0);
    }
}
