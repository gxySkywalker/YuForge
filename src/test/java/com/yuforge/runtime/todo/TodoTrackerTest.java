package com.yuforge.runtime.todo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoTrackerTest {
    @Test
    void shouldKeepStructuredTodoAsSessionWorkMemory() {
        TodoTracker tracker = new TodoTracker();
        String result = tracker.rewrite("[{\"id\":\"todo_1\",\"content\":\"定位入口\",\"status\":\"in_progress\"},{\"id\":\"todo_2\",\"content\":\"补测试\",\"status\":\"pending\"}]");
        assertTrue(result.contains("TODO 0/2 · 1 active"));
        assertTrue(tracker.contextBlock().contains("[~] todo_1: 定位入口"));
        tracker.update("todo_1", "completed", null);
        assertEquals("TODO 1/2", tracker.summary());
        tracker.clear();
        assertEquals("", tracker.summary());
    }

    @Test
    void shouldRejectInvalidStatusWithoutReplacingCurrentList() {
        TodoTracker tracker = new TodoTracker();
        tracker.rewrite("[{\"id\":\"todo_1\",\"content\":\"定位入口\",\"status\":\"pending\"}]");
        String result = tracker.update("todo_1", "doing", null);
        assertTrue(result.contains("status 只能是"));
        assertEquals("TODO 0/1", tracker.summary());
    }
}
