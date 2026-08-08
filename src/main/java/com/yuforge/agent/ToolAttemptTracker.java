package com.yuforge.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuforge.tool.ToolRegistry.ToolExecutionResult;
import com.yuforge.tool.ToolResultDiagnostic;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * 单次 Agent 运行内的工具尝试计数器。
 *
 * <p>调用次数按“工具名 + 规范化参数”统计；同类失败次数额外加入错误码，避免把读取
 * 不同文件或不同失败原因误判成同一次盲目重试。</p>
 */
public final class ToolAttemptTracker {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Integer> callAttempts = new HashMap<>();
    private final Map<String, Integer> errorAttempts = new HashMap<>();

    public Observation observe(ToolExecutionResult result) {
        String signature = result.name() + "\n" + canonicalArguments(result.argumentsJson());
        int attempt = callAttempts.merge(signature, 1, Integer::sum);
        ToolResultDiagnostic diagnostic = result.diagnostic();
        int sameErrorAttempt = diagnostic.failed()
                ? errorAttempts.merge(signature + "\n" + diagnostic.code(), 1, Integer::sum)
                : 0;
        return new Observation(attempt, sameErrorAttempt, diagnostic, formatForModel(result, attempt, sameErrorAttempt));
    }

    private String formatForModel(ToolExecutionResult result, int attempt, int sameErrorAttempt) {
        ToolResultDiagnostic diagnostic = result.diagnostic();
        if (!diagnostic.failed() && attempt == 1) {
            return result.result();
        }

        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("status", diagnostic.failed() ? "error" : "success");
        envelope.put("tool", result.name());
        envelope.put("attempt", attempt);
        envelope.put("duration_ms", result.elapsedMillis());
        if (diagnostic.failed()) {
            envelope.put("code", diagnostic.code().name());
            envelope.put("retryable", diagnostic.retryable());
            envelope.put("same_error_attempt", sameErrorAttempt);
            envelope.put("suggestion", recoverySuggestion(diagnostic, sameErrorAttempt));
        } else {
            envelope.put("repeated_signature", true);
            envelope.put("suggestion", "相同工具和参数已执行过；确认新调用确有必要，避免重复获取相同结果。");
        }
        envelope.put("message", result.result());
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            return result.result();
        }
    }

    private static String recoverySuggestion(ToolResultDiagnostic diagnostic, int sameErrorAttempt) {
        if (!diagnostic.retryable()) {
            return diagnostic.suggestion();
        }
        if (sameErrorAttempt >= 3) {
            return "禁止原样重试。" + diagnostic.suggestion() + " 若仍无法推进，向用户报告阻塞和已尝试方案。";
        }
        if (sameErrorAttempt >= 2) {
            return "不要使用相同参数重试，必须改变参数或切换工具。" + diagnostic.suggestion();
        }
        return diagnostic.suggestion();
    }

    private String canonicalArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return "{}";
        }
        try {
            return canonicalize(mapper.readTree(argumentsJson)).toString();
        } catch (Exception ignored) {
            return argumentsJson.trim();
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode array = mapper.createArrayNode();
            for (JsonNode child : node) {
                array.add(canonicalize(child));
            }
            return array;
        }
        TreeMap<String, JsonNode> sorted = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            sorted.put(field.getKey(), canonicalize(field.getValue()));
        }
        ObjectNode object = mapper.createObjectNode();
        sorted.forEach(object::set);
        return object;
    }

    public record Observation(
            int attempt,
            int sameErrorAttempt,
            ToolResultDiagnostic diagnostic,
            String modelResult
    ) {
    }
}
