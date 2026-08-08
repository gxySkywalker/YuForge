package com.yuforge.memory;

import java.util.Locale;

/**
 * 长期记忆写入的代码级授权边界。
 *
 * 模型、网页、MCP 和工具结果都不能自行授予写记忆权限；权限只能从本轮原始用户输入取得。
 */
public final class MemoryWritePolicy {
    private MemoryWritePolicy() {
    }

    public static Authorization fromUserInput(String userInput) {
        String normalized = userInput == null ? "" : userInput.trim().toLowerCase(Locale.ROOT);
        boolean explicitSave = normalized.contains("记一下")
                || normalized.contains("记住")
                || normalized.contains("记下来")
                || normalized.contains("以后记得")
                || normalized.contains("下次记得")
                || normalized.contains("保存这个偏好")
                || normalized.contains("保存到长期记忆");
        boolean explicitGlobal = explicitSave && (normalized.contains("全局")
                || normalized.contains("跨项目")
                || normalized.contains("所有项目"));
        return new Authorization(explicitSave, explicitGlobal);
    }

    public record Authorization(boolean allowsProject, boolean allowsGlobal) {
        public static Authorization denyAll() {
            return new Authorization(false, false);
        }
    }
}
