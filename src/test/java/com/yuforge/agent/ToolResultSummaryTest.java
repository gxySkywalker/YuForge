package com.yuforge.agent;

import com.yuforge.tool.ToolRegistry.ToolExecutionResult;
import com.yuforge.tool.ToolResultDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultSummaryTest {

    @Test
    void rendersSuccessWithSafeTargetAndElapsedTime() {
        ToolExecutionResult result = new ToolExecutionResult("id", "read_file", "{\"path\":\"src/App.java\"}",
                "文件内容: secret-token-must-not-be-printed", 123L, false, List.of(), ToolResultDiagnostic.success());

        String summary = ToolResultSummary.format(result);

        assertTrue(summary.contains("[ok] 读取 src/App.java完成 · 123ms"), summary);
        assertFalse(summary.contains("secret-token"), summary);
    }

    @Test
    void rendersFailureWithoutRawErrorText() {
        ToolExecutionResult result = new ToolExecutionResult("id", "execute_command", "{\"command\":\"echo $SECRET\"}",
                "command output: super-secret", 2200L, false, List.of(),
                new ToolResultDiagnostic(ToolResultDiagnostic.Status.ERROR,
                        ToolResultDiagnostic.ErrorCode.COMMAND_FAILED, true,
                        "先分析退出码和输出中的首个根因；修正命令、工作目录或依赖后再重试。"));

        String summary = ToolResultSummary.format(result);

        assertTrue(summary.contains("[warn] 执行命令失败 (COMMAND_FAILED) · 2.2s"), summary);
        assertTrue(summary.contains("建议：先分析退出码和输出中的首个根因"), summary);
        assertFalse(summary.contains("super-secret"), summary);
    }

    @Test
    void rendersTimeoutAndCancellationAsDistinctTerminalStates() {
        ToolExecutionResult timeout = new ToolExecutionResult("id", "grep_code", "{}", "", 60_000L,
                true, List.of(), ToolResultDiagnostic.timeout());
        ToolExecutionResult cancelled = new ToolExecutionResult("id", "read_file", "{}", "", 0L,
                false, List.of(), new ToolResultDiagnostic(ToolResultDiagnostic.Status.ERROR,
                        ToolResultDiagnostic.ErrorCode.INTERRUPTED, false, ""));

        assertTrue(ToolResultSummary.format(timeout).contains("超时，已取消"));
        assertTrue(ToolResultSummary.format(cancelled).contains("已取消"));
    }
}
