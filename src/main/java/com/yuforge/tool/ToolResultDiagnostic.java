package com.yuforge.tool;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面向 Agent 恢复决策的精简工具结果诊断。
 *
 * <p>工具的完整原始输出仍由 {@code ToolExecutionResult.result()} 保存；本类型只提供
 * 稳定错误码、是否值得重试以及短恢复建议，避免把 Java 调用栈回灌给模型。</p>
 */
public record ToolResultDiagnostic(
        Status status,
        ErrorCode code,
        boolean retryable,
        String suggestion
) {
    private static final Pattern COMMAND_EXIT_CODE =
            Pattern.compile("(?i)命令执行完成\\s*\\(exit code:\\s*(-?\\d+)\\)");

    public enum Status {
        SUCCESS,
        ERROR
    }

    public enum ErrorCode {
        NONE,
        FILE_NOT_FOUND,
        INVALID_ARGUMENT,
        TIMEOUT,
        POLICY_DENIED,
        PERMISSION_DENIED,
        COMMAND_FAILED,
        UNKNOWN_TOOL,
        INTERRUPTED,
        TOOL_ERROR
    }

    public static ToolResultDiagnostic success() {
        return new ToolResultDiagnostic(Status.SUCCESS, ErrorCode.NONE, false, "");
    }

    public static ToolResultDiagnostic timeout() {
        return error(ErrorCode.TIMEOUT, true, "缩小操作范围、拆分命令或减少单次返回量后重试。");
    }

    public static ToolResultDiagnostic classify(String result, boolean timedOut) {
        if (timedOut) {
            return timeout();
        }
        String text = result == null ? "" : result.trim();
        String lower = text.toLowerCase(Locale.ROOT);

        if (startsWithAny(text, "🛡️ 策略拒绝:", "策略拒绝:")) {
            return error(ErrorCode.POLICY_DENIED, false,
                    "不要原样重试；改用策略允许的路径、参数或操作，并在需要时向用户说明限制。");
        }
        if (startsWithAny(text, "未知工具:")) {
            return error(ErrorCode.UNKNOWN_TOOL, false, "重新检查当前工具列表和工具名称。");
        }
        if (startsWithAny(text, "用户取消了此次工具调用", "工具执行被中断", "工具批次执行被中断")) {
            return error(ErrorCode.INTERRUPTED, false, "停止自动重试并尊重取消或中断信号。");
        }
        if (startsWithAny(text, "工具执行超时", "命令执行超时") || lower.startsWith("timed out")) {
            return timeout();
        }

        Matcher exitMatcher = COMMAND_EXIT_CODE.matcher(text);
        if (exitMatcher.find()) {
            try {
                if (Integer.parseInt(exitMatcher.group(1)) != 0) {
                    return error(ErrorCode.COMMAND_FAILED, true,
                            "先分析退出码和输出中的首个根因；修正命令、工作目录或依赖后再重试。");
                }
            } catch (NumberFormatException ignored) {
                return error(ErrorCode.COMMAND_FAILED, true, "检查命令输出并改变执行策略。");
            }
        }

        boolean knownFailure = startsWithAny(text,
                "工具执行失败:", "读取文件失败:", "写入文件失败:", "执行命令失败:",
                "搜索失败", "恢复工具结果失败:", "创建项目失败:", "列出目录失败:");
        if (!knownFailure && !"目录为空或不存在".equals(text)) {
            return success();
        }

        if (containsAny(lower, "不存在", "not found", "no such file", "不是普通文件")) {
            return error(ErrorCode.FILE_NOT_FOUND, true,
                    "先核对工作目录；使用 glob_files 或 list_dir 定位真实路径，再读取目标。");
        }
        if (containsAny(lower, "不能为空", "invalid argument", "invalid json", "解析", "argument")) {
            return error(ErrorCode.INVALID_ARGUMENT, true, "根据工具 schema 修正缺失或无效参数后重试。");
        }
        if (containsAny(lower, "permission denied", "access denied", "无权限", "拒绝访问")) {
            return error(ErrorCode.PERMISSION_DENIED, false,
                    "不要重复相同操作；检查项目路径边界或请求用户提供必要权限。");
        }
        return error(ErrorCode.TOOL_ERROR, true, "分析错误信息并改变参数、输入范围或工具选择。");
    }

    public boolean failed() {
        return status == Status.ERROR;
    }

    private static ToolResultDiagnostic error(ErrorCode code, boolean retryable, String suggestion) {
        return new ToolResultDiagnostic(Status.ERROR, code, retryable, suggestion);
    }

    private static boolean startsWithAny(String text, String... prefixes) {
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String text, String... fragments) {
        for (String fragment : fragments) {
            if (text.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
