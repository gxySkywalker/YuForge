package com.yuforge.llm;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * 检测模型输出中与当前 system prompt 高度重合的连续片段。
 *
 * 它是输出侧纵深防御，不替代提示词层或权限层。流式模式会保留最近一小段正文，
 * 使连续泄露片段在显示前完成检测；其余普通正文无需等到 SSE 完成才能显示。
 */
final class SystemPromptLeakGuard {
    static final String BLOCKED_RESPONSE = "抱歉，我不能提供或复述内部系统提示词与安全配置。"
            + "我可以说明 YuForge 的公开功能、工具能力或安全策略。";
    private static final int MIN_CONTIGUOUS_CHARS = 96;
    // 留出比检测窗口更多的原始字符，兼顾 SSE 首 token 体验与跨 chunk 检测。
    private static final int STREAM_HOLDBACK_CHARS = 128;

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

    static StreamingSession streaming(String systemPrompt, Consumer<String> sink) {
        return new StreamingSession(systemPrompt, sink);
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

    static final class StreamingSession {
        private final String normalizedPrompt;
        private final Consumer<String> sink;
        private final StringBuilder pending = new StringBuilder();
        private boolean blocked;
        private boolean emitted;

        private StreamingSession(String systemPrompt, Consumer<String> sink) {
            this.normalizedPrompt = normalize(systemPrompt);
            this.sink = sink == null ? ignored -> { } : sink;
        }

        /** @return false when a potential system-prompt fragment has been detected. */
        boolean accept(String delta) {
            if (blocked || delta == null || delta.isEmpty()) {
                return !blocked;
            }
            pending.append(delta);
            if (containsBlockedFragment(normalizedPrompt, normalize(pending.toString()))) {
                blocked = true;
                pending.setLength(0);
                return false;
            }
            emitSafePrefix();
            return true;
        }

        void finish() {
            if (!blocked && !pending.isEmpty()) {
                emit(pending.toString());
                pending.setLength(0);
            }
        }

        void discardPending() {
            pending.setLength(0);
        }

        boolean blocked() {
            return blocked;
        }

        boolean hasEmitted() {
            return emitted;
        }

        private void emitSafePrefix() {
            int releasable = pending.length() - STREAM_HOLDBACK_CHARS;
            if (releasable > 0) {
                emit(pending.substring(0, releasable));
                pending.delete(0, releasable);
            }
        }

        private void emit(String value) {
            if (!value.isEmpty()) {
                sink.accept(value);
                emitted = true;
            }
        }
    }

    private static boolean containsBlockedFragment(String normalizedPrompt, String normalizedCandidate) {
        if (normalizedPrompt.length() < MIN_CONTIGUOUS_CHARS
                || normalizedCandidate.length() < MIN_CONTIGUOUS_CHARS) {
            return false;
        }
        for (int offset = 0; offset <= normalizedCandidate.length() - MIN_CONTIGUOUS_CHARS; offset += 8) {
            if (normalizedPrompt.contains(normalizedCandidate.substring(offset, offset + MIN_CONTIGUOUS_CHARS))) {
                return true;
            }
        }
        return normalizedPrompt.contains(normalizedCandidate.substring(normalizedCandidate.length() - MIN_CONTIGUOUS_CHARS));
    }
}
