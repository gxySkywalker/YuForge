package com.yuforge.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultDiagnosticTest {

    @Test
    void classifiesKnownFailuresWithoutTreatingNormalContentAsError() {
        ToolResultDiagnostic missing = ToolResultDiagnostic.classify(
                "读取文件失败: src/Missing.java 不存在", false);
        assertEquals(ToolResultDiagnostic.ErrorCode.FILE_NOT_FOUND, missing.code());
        assertTrue(missing.retryable());

        ToolResultDiagnostic denied = ToolResultDiagnostic.classify(
                "🛡️ 策略拒绝: 路径超出项目根目录", false);
        assertEquals(ToolResultDiagnostic.ErrorCode.POLICY_DENIED, denied.code());
        assertFalse(denied.retryable());

        ToolResultDiagnostic success = ToolResultDiagnostic.classify(
                "README 中写着：missing file 不存在时返回 404", false);
        assertEquals(ToolResultDiagnostic.Status.SUCCESS, success.status());
    }

    @Test
    void classifiesNonZeroCommandExitAndTimeout() {
        ToolResultDiagnostic command = ToolResultDiagnostic.classify(
                "命令执行完成 (exit code: 1)\ncompilation failed", false);
        assertEquals(ToolResultDiagnostic.ErrorCode.COMMAND_FAILED, command.code());
        assertTrue(command.retryable());

        assertEquals(ToolResultDiagnostic.ErrorCode.TIMEOUT,
                ToolResultDiagnostic.classify("ignored", true).code());
    }
}
