package com.yuforge.runtime.todo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 会话内 TODO：Agent 的外部工作记忆，不落盘、不写入长期记忆。 */
public final class TodoTracker {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Map<String, TodoItem> items = new LinkedHashMap<>();

    public synchronized String rewrite(String itemsJson) {
        if (itemsJson == null || itemsJson.isBlank()) return "TODO 更新失败: items 不能为空";
        try {
            JsonNode array = MAPPER.readTree(itemsJson);
            if (!array.isArray()) return "TODO 更新失败: items 必须是 JSON 数组";
            Map<String, TodoItem> next = new LinkedHashMap<>();
            for (JsonNode node : array) {
                String id = text(node, "id");
                String content = text(node, "content");
                TodoStatus status = TodoStatus.parse(text(node, "status"));
                if (id.isBlank() || content.isBlank() || status == null) {
                    return "TODO 更新失败: 每项需要 id、content，status 只能是 pending/in_progress/completed/cancelled";
                }
                next.put(id, new TodoItem(id, content, status));
            }
            items.clear();
            items.putAll(next);
            return format();
        } catch (Exception e) {
            return "TODO 更新失败: items 不是合法 JSON 数组";
        }
    }

    public synchronized String update(String id, String status, String content) {
        if (id == null || id.isBlank()) return "TODO 更新失败: id 不能为空";
        TodoItem previous = items.get(id.trim());
        if (previous == null) return "TODO 更新失败: 未找到任务 " + id.trim();
        TodoStatus nextStatus = status == null || status.isBlank() ? previous.status() : TodoStatus.parse(status);
        if (nextStatus == null) return "TODO 更新失败: status 只能是 pending/in_progress/completed/cancelled";
        items.put(previous.id(), new TodoItem(previous.id(),
                content == null || content.isBlank() ? previous.content() : content.trim(), nextStatus));
        return format();
    }

    public synchronized String contextBlock() {
        if (items.isEmpty()) return "";
        StringBuilder result = new StringBuilder("<todo_list>\n这是当前任务的外部工作记忆；继续执行时更新状态，完成前核对未完成项。\n");
        for (TodoItem item : items.values()) result.append("- [").append(item.status().marker()).append("] ")
                .append(item.id()).append(": ").append(item.content()).append('\n');
        return result.append("</todo_list>").toString();
    }

    public synchronized String snapshotJson() {
        try {
            var array = MAPPER.createArrayNode();
            for (TodoItem item : items.values()) {
                var node = array.addObject();
                node.put("id", item.id());
                node.put("content", item.content());
                node.put("status", item.status().name().toLowerCase(Locale.ROOT));
            }
            return MAPPER.writeValueAsString(array);
        } catch (Exception e) {
            return "[]";
        }
    }

    public synchronized void restore(String itemsJson) {
        if (itemsJson == null || itemsJson.isBlank()) { items.clear(); return; }
        String result = rewrite(itemsJson);
        if (result.startsWith("TODO 更新失败")) items.clear();
    }

    public synchronized String summary() {
        if (items.isEmpty()) return "";
        long done = items.values().stream().filter(item -> item.status() == TodoStatus.COMPLETED).count();
        long active = items.values().stream().filter(item -> item.status() == TodoStatus.IN_PROGRESS).count();
        return "TODO " + done + "/" + items.size() + (active > 0 ? " · " + active + " active" : "");
    }

    public synchronized void clear() {
        items.clear();
    }

    private String format() {
        return "TODO 已更新: " + summary();
    }

    private static String text(JsonNode node, String name) { return node.path(name).asText("").trim(); }
    private record TodoItem(String id, String content, TodoStatus status) { }
    private enum TodoStatus {
        PENDING(" "), IN_PROGRESS("~"), COMPLETED("x"), CANCELLED("-");
        private final String marker;
        TodoStatus(String marker) { this.marker = marker; }
        String marker() { return marker; }
        static TodoStatus parse(String value) {
            String normalized = value == null || value.isBlank() ? "pending" : value.toUpperCase(Locale.ROOT);
            try { return valueOf(normalized); } catch (IllegalArgumentException e) { return null; }
        }
    }
}
