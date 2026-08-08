package com.yuforge.hitl;

import com.yuforge.browser.BrowserCheckResult;
import com.yuforge.policy.AuditLog;
import com.yuforge.tool.ToolOutput;
import com.yuforge.tool.ToolRegistry;

import java.util.concurrent.TimeUnit;

/**
 * HITL 工具注册表 - 在危险工具调用前插入人工审批
 *
 * 继承自 ToolRegistry，覆写 executeTool 方法，在执行危险操作之前
 * 通过 HitlHandler 向用户请求审批。
 *
 * 如果 HITL 未启用，行为与父类完全相同，无额外开销。
 *
 * HITL 拒绝 / 跳过路径会写一行 audit（approver=hitl），HITL 通过后由父类 ToolRegistry 写
 * allow / policy-deny / error，HITL 审批与策略拦截共用同一份 ~/.yuforge/audit/ 文件。
 */
public class HitlToolRegistry extends ToolRegistry {

    private final HitlHandler hitlHandler;

    public HitlToolRegistry(HitlHandler hitlHandler) {
        super();
        this.hitlHandler = hitlHandler;
    }

    @Override
    public String executeTool(String name, String argumentsJson) {
        return executeToolOutput(name, argumentsJson).text();
    }

    @Override
    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        if (requiresExternalContentApproval(name)) {
            BrowserCheckResult browserCheck = checkBrowserTool(name, argumentsJson, true);
            if (browserCheck.blocked()) {
                return super.doExecuteTool(name, argumentsJson);
            }
            String notice = "本轮已读取不可信网页或 MCP 内容；即使常规 HITL 已关闭，此副作用操作也必须逐次确认。";
            if (browserCheck.requiresPerCallApproval()) {
                notice += " " + browserCheck.sensitiveNotice();
            }
            return executeAfterExplicitApproval(name, argumentsJson, notice);
        }
        // HITL 未启用或该工具不需要审批，直接执行
        if (!hitlHandler.isEnabled() || !ApprovalPolicy.requiresApproval(name)) {
            return super.doExecuteTool(name, argumentsJson);
        }
        BrowserCheckResult browserCheck = checkBrowserTool(name, argumentsJson, true);
        if (browserCheck.blocked()) {
            return super.doExecuteTool(name, argumentsJson);
        }
        if (browserCheck.requiresPerCallApproval()) {
            return executeAfterExplicitApproval(name, argumentsJson, browserCheck.sensitiveNotice());
        }
        String mcpServer = ApprovalPolicy.mcpServerName(name);
        if (hitlHandler.isApprovedAllByTool(name) || hitlHandler.isApprovedAllByServer(mcpServer)) {
            return super.doExecuteTool(name, argumentsJson);
        }

        return executeAfterExplicitApproval(name, argumentsJson, null);
    }

    private boolean requiresExternalContentApproval(String toolName) {
        if (!hasUntrustedContentObserved()) {
            return false;
        }
        return ApprovalPolicy.requiresApproval(toolName) || "save_memory".equals(toolName);
    }

    private ToolOutput executeAfterExplicitApproval(String name, String argumentsJson, String sensitiveNotice) {
        long start = System.nanoTime();
        ApprovalRequest request = ApprovalRequest.of(name, argumentsJson, null, null, sensitiveNotice);
        ApprovalResult result = hitlHandler.requestApproval(request);

        if (result.isRejected()) {
            String reason = result.reason() != null && !result.reason().isBlank()
                    ? result.reason()
                    : "用户拒绝了此操作";
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    name, argumentsJson, reason, elapsedMillis(start)));
            return ToolOutput.text("[HITL] 操作已被拒绝：" + reason);
        }

        if (result.isSkipped()) {
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    name, argumentsJson, "用户跳过", elapsedMillis(start)));
            return ToolOutput.text("[HITL] 操作已被跳过");
        }

        // 批准（含修改参数）- 使用 effectiveArguments 获取最终参数；父类执行路径会负责 allow audit
        String effectiveArgs = result.effectiveArguments(argumentsJson);
        return super.doExecuteTool(name, effectiveArgs);
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    public HitlHandler getHitlHandler() {
        return hitlHandler;
    }
}
