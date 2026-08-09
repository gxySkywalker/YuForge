package com.yuforge.agent;

import com.yuforge.tool.ToolRegistry.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeVerificationTrackerTest {

    @Test
    void reportsUnverifiedAfterSuccessfulPatchWithoutEvidence() {
        ChangeVerificationTracker tracker = new ChangeVerificationTracker();
        tracker.observe(result("apply_patch", "补丁已应用: App.java"));

        assertTrue(tracker.changedWorkspace());
        assertFalse(tracker.hasEvidence());
        assertTrue(tracker.statusLine().contains("没有构建、测试"));
    }

    @Test
    void acceptsSuccessfulBuildAsEvidenceAfterChange() {
        ChangeVerificationTracker tracker = new ChangeVerificationTracker();
        tracker.observe(result("apply_patch", "补丁已应用: App.java"));
        tracker.observe(result("execute_command", "命令执行完成 (exit code: 0)\nBUILD SUCCESS"));

        assertTrue(tracker.hasEvidence());
        assertTrue(tracker.statusLine().startsWith("✅ 验证"));
    }

    @Test
    void failedWriteDoesNotClaimWorkspaceChanged() {
        ChangeVerificationTracker tracker = new ChangeVerificationTracker();
        tracker.observe(result("write_file", "写入文件失败: 拒绝访问"));

        assertFalse(tracker.changedWorkspace());
    }

    private static ToolExecutionResult result(String name, String text) {
        return new ToolExecutionResult("call", name, "{}", text, 1, false, List.of(), null);
    }
}
