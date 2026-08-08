package com.yuforge.llm;

import java.util.Locale;

/**
 * 检测模型输出中与当前 system prompt 高度重合的连续片段。
 *
 * 它是输出侧纵深防御，不替代提示词层或权限层；为避免流式内容在命中后已经显示，
 * OpenAI-compatible client 会在完成扫描后才向 UI 提交正文。
 */
final class SystemPromptLeakGuard {
    static final String BLOCKED_RESPONSE = "抱歉，我不能提供或复述内部系统提示词与安全配置。"
            + "我可以说明 YuForge 的公开功能、工具能力或安全策略。";
    private static final int MIN_CONTIGUOUS_CHARS = 96;

    private SystemPromptLeakGuard() {
    }

    static Decision inspect(String systemPrompt, String candidate) {
        String normalizedPrompt = normalize(systemPrompt);
        String normalizedCandidate = normalize(candidate);
        if (normalizedPrompt.length() < MIN_CONTIGUOUS_CHARS
                || normalizedCandidate.length() < MIN_CONTIGUOUS_CHARS) {
            return Decision.allow(candidate);
        }
        for (int offset = 0; offset <= normalizedCandidate.length() - MIN_CONTIGUOUS_CHARS; offset += 8) {
            String window = normalizedCandidate.substring(offset, offset + MIN_CONTIGUOUS_CHARS);
            if (normalizedPrompt.contains(window)) {
                return Decision.block();
            }
        }
        // 覆盖末尾窗口，避免长度未被 8 整除时漏检。
        String tail = normalizedCandidate.substring(normalizedCandidate.length() - MIN_CONTIGUOUS_CHARS);
        if (normalizedPrompt.contains(tail)) {
            return Decision.block();
        }
        return Decision.allow(candidate);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    record Decision(boolean blocked, String safeContent) {
        static Decision allow(String content) {
            return new Decision(false, content == null ? "" : content);
        }

        static Decision block() {
            return new Decision(true, BLOCKED_RESPONSE);
        }
    }
}
