package com.yuforge.memory;

import java.util.List;

/**
 * 最近一次长期记忆注入的本地诊断信息。
 *
 * 不包含原始用户查询或记忆正文，避免调试状态本身成为新的隐私泄漏源；
 * 也不会被写入模型消息。用于 /memory status、/context 和日志排障。
 */
public record MemoryRetrievalTrace(
        int queryLength,
        int queryTokenCount,
        int eligibleCount,
        int budgetTokens,
        int injectedTokens,
        List<Selection> injected,
        List<Selection> omittedByBudget
) {
    public MemoryRetrievalTrace {
        injected = List.copyOf(injected == null ? List.of() : injected);
        omittedByBudget = List.copyOf(omittedByBudget == null ? List.of() : omittedByBudget);
    }

    public static MemoryRetrievalTrace empty() {
        return new MemoryRetrievalTrace(0, 0, 0, 0, 0, List.of(), List.of());
    }

    public boolean hasQuery() {
        return queryLength > 0;
    }

    public String formatForStatus() {
        if (!hasQuery()) return "最近一次长期记忆检索: 尚未执行";
        StringBuilder result = new StringBuilder("最近一次长期记忆检索: queryTokens=")
                .append(queryTokenCount)
                .append(", 命中=").append(eligibleCount)
                .append(", 注入=").append(injected.size())
                .append("/").append(budgetTokens).append(" tokens")
                .append(" (实际 ").append(injectedTokens).append(")");
        if (!injected.isEmpty()) result.append("\n  注入: ").append(injected);
        if (!omittedByBudget.isEmpty()) result.append("\n  因预算未注入: ").append(omittedByBudget);
        return result.toString();
    }

    public record Selection(String id, double score, int tokens) {
        @Override
        public String toString() {
            return id + "(score=" + String.format(java.util.Locale.ROOT, "%.2f", score) + ", " + tokens + "t)";
        }
    }
}
