package com.yuforge.agent;

import com.yuforge.tool.ToolRegistry.ToolExecutionResult;

import java.util.Set;

/**
 * 为一次 Agent turn 提供可见、可审计的改动验证状态；它不替模型猜测命令，
 * 只依据实际执行过的工具结果决定能否声称存在验证证据。
 */
final class ChangeVerificationTracker {
    private static final Set<String> MUTATING_TOOLS = Set.of(
            "write_file", "apply_patch", "create_project", "revert_turn", "start_background_process"
    );
    private boolean changedWorkspace;
    private String evidence;

    void observe(ToolExecutionResult result) {
        if (result == null) {
            return;
        }
        String name = result.name();
        if (MUTATING_TOOLS.contains(name) && !isFailure(result.result())) {
            changedWorkspace = true;
            return;
        }
        if (!changedWorkspace || isFailure(result.result())) {
            return;
        }
        if ("execute_command".equals(name)) {
            evidence = "命令已执行并返回成功退出码";
        } else if ("wait_background_process_ready".equals(name) && result.result().contains("服务诊断: ready")) {
            evidence = "开发服务已报告就绪";
        } else if ("read_file".equals(name)) {
            evidence = "已回读修改后的文件";
        }
    }

    boolean changedWorkspace() {
        return changedWorkspace;
    }

    boolean hasEvidence() {
        return evidence != null;
    }

    String statusLine() {
        if (!changedWorkspace) {
            return "";
        }
        return evidence == null
                ? "⚠️ 验证：已修改工作区，但本轮没有构建、测试、服务就绪或回读证据；不要将结果视为已验证完成。"
                : "✅ 验证：" + evidence + "。";
    }

    private static boolean isFailure(String result) {
        String text = result == null ? "" : result;
        return text.contains("失败") || text.contains("拒绝") || text.contains("超时")
                || text.contains("命令执行完成 (exit code: 0)") == false && text.contains("命令执行完成");
    }
}
