package com.yuforge.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuforge.tool.ToolRegistry.ToolExecutionResult;
import com.yuforge.tool.ToolResultDiagnostic;

/**
 * 面向终端用户的工具完成摘要。
 *
 * <p>只展示操作、关键对象、终态和耗时，不回显工具原文或错误正文；完整结果仍只进入
 * Agent 消息历史和调试日志，避免终端噪音或意外展示敏感输出。</p>
 */
final class ToolResultSummary {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolResultSummary() {
    }

    static String format(ToolExecutionResult result) {
        if (result == null || result.name() == null || result.name().isBlank()) {
            return "";
        }
        String action = action(result.name(), result.argumentsJson());
        long elapsed = Math.max(0L, result.elapsedMillis());
        ToolResultDiagnostic diagnostic = result.diagnostic();
        if (result.timedOut() || diagnostic != null && diagnostic.code() == ToolResultDiagnostic.ErrorCode.TIMEOUT) {
            return "[warn] " + action + "超时，已取消" + elapsed(elapsed);
        }
        if (diagnostic != null && diagnostic.code() == ToolResultDiagnostic.ErrorCode.INTERRUPTED) {
            return "[stop] " + action + "已取消" + elapsed(elapsed);
        }
        if (diagnostic != null && diagnostic.failed()) {
            return "[warn] " + action + "失败 (" + diagnostic.code() + ")" + elapsed(elapsed)
                    + compactSuggestion(diagnostic.suggestion());
        }
        return "[ok] " + action + "完成" + elapsed(elapsed);
    }

    private static String action(String name, String argumentsJson) {
        String target = keyArgument(name, argumentsJson);
        String suffix = target.isBlank() ? "" : " " + target;
        return switch (name) {
            case "read_file" -> "读取" + suffix;
            case "write_file" -> "写入" + suffix;
            case "list_dir" -> "列出目录" + suffix;
            case "glob_files" -> "查找文件" + suffix;
            case "grep_code" -> "搜索代码" + suffix;
            case "execute_command" -> "执行命令";
            case "start_background_process" -> "启动开发服务";
            case "list_background_processes" -> "查看后台服务";
            case "read_background_process_log" -> "读取服务日志";
            case "inspect_background_process" -> "诊断开发服务";
            case "wait_background_process_ready" -> "等待服务就绪";
            case "stop_background_process" -> "停止开发服务";
            case "create_project" -> "创建项目" + suffix;
            case "search_code" -> "语义检索代码";
            case "web_search" -> "联网搜索";
            case "web_fetch" -> "抓取网页";
            case "save_memory" -> "保存长期记忆";
            case "read_tool_artifact" -> "恢复工具归档" + suffix;
            case "rewrite_todo_list" -> "更新 TODO 清单";
            case "update_todo_status" -> "更新 TODO" + suffix;
            case "revert_turn" -> "恢复快照";
            default -> name.startsWith("mcp__") ? "调用 MCP 工具 " + mcpName(name) : "调用 " + name;
        };
    }

    private static String keyArgument(String toolName, String argumentsJson) {
        String key = switch (toolName) {
            case "read_file", "write_file", "apply_patch", "list_dir" -> "path";
            case "glob_files" -> "pattern";
            case "create_project" -> "name";
            case "start_background_process" -> "command";
            case "read_background_process_log", "inspect_background_process",
                    "wait_background_process_ready", "stop_background_process" -> "process_id";
            case "read_tool_artifact" -> "artifact_id";
            case "update_todo_status" -> "id";
            default -> "";
        };
        if (key.isEmpty() || argumentsJson == null || argumentsJson.isBlank()) {
            return "";
        }
        try {
            JsonNode node = JSON.readTree(argumentsJson);
            return compact(node.path(key).asText(""));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String mcpName(String name) {
        String[] parts = name.split("__", 3);
        return parts.length == 3 ? parts[1] + "." + parts[2] : name;
    }

    private static String compact(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() > 72 ? normalized.substring(0, 69) + "..." : normalized;
    }

    private static String elapsed(long elapsedMillis) {
        if (elapsedMillis <= 0L) {
            return "";
        }
        return elapsedMillis < 1_000L
                ? " · " + elapsedMillis + "ms"
                : String.format(" · %.1fs", elapsedMillis / 1_000.0);
    }

    private static String compactSuggestion(String suggestion) {
        String value = compact(suggestion);
        return value.isBlank() ? "" : "；建议：" + value;
    }
}
