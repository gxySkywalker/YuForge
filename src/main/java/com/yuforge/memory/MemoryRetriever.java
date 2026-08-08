package com.yuforge.memory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆检索器 - 根据查询从短期记忆和长期记忆中检索最相关的信息
 *
 * 检索策略：
 * 1. 关键词匹配：直接匹配内容中的关键词
 * 2. 类型优先：不同场景优先检索不同类型的记忆
 * 3. 时间衰减：越近的记忆权重越高
 */
public class MemoryRetriever {
    private final ConversationMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private final MemoryRetrievalStrategy retrievalStrategy;

    public MemoryRetriever(ConversationMemory shortTermMemory, LongTermMemory longTermMemory) {
        this(shortTermMemory, longTermMemory, new KeywordMemoryRetrievalStrategy());
    }

    public MemoryRetriever(ConversationMemory shortTermMemory, LongTermMemory longTermMemory,
                           MemoryRetrievalStrategy retrievalStrategy) {
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
        this.retrievalStrategy = retrievalStrategy == null ? new KeywordMemoryRetrievalStrategy() : retrievalStrategy;
    }

    /**
     * 检索与查询最相关的记忆
     *
     * @param query 查询文本
     * @param limit 返回条数上限
     * @return 按相关度排序的记忆列表
     */
    public List<MemoryEntry> retrieve(String query, int limit) {
        List<ScoredEntry> scored = new ArrayList<>();

        retrievalStrategy.rank(shortTermMemory.getAll(), query, limit)
                .forEach(match -> scored.add(new ScoredEntry(match.entry(), match.score(), true)));
        // 长期记忆是人工确认的稳定事实，在同等相关度下优先于短期对话。
        retrievalStrategy.rank(longTermMemory.getAll(), query, limit)
                .forEach(match -> scored.add(new ScoredEntry(match.entry(), match.score() * 1.2, false)));

        // 按分数降序排序
        return scored.stream()
                .sorted(scoredEntryComparator())
                .limit(limit)
                .map(ScoredEntry::entry)
                .collect(Collectors.toList());
    }

    /**
     * 仅从长期记忆中检索稳定事实，用于 system prompt 注入。
     *
     * 当前轮用户输入和短期对话已经在 message history 里，不应再次以"相关记忆"身份
     * 注入给模型，否则容易让模型把当前请求误读成历史事实。
     */
    public List<MemoryEntry> retrieveLongTerm(String query, int limit) {
        return retrieveLongTerm(query, limit, null);
    }

    public List<MemoryEntry> retrieveLongTerm(String query, int limit, String projectKey) {
        return retrieveLongTermMatches(query, limit, projectKey).stream().map(ScoredEntry::entry).toList();
    }

    private List<ScoredEntry> retrieveLongTermMatches(String query, int limit, String projectKey) {
        return retrievalStrategy.rank(longTermMemory.getAll().stream()
                        .filter(entry -> LongTermMemory.isVisibleInProject(entry, projectKey)).toList(), query, limit)
                .stream()
                .map(match -> new ScoredEntry(match.entry(), match.score() * 1.2, false))
                .toList();
    }

    /**
     * 构建上下文：将相关记忆组装成文本，用于注入到 LLM 的 system prompt 中
     */
    public String buildContextForQuery(String query, int maxTokens) {
        return buildContextForQuery(query, maxTokens, null);
    }

    public String buildContextForQuery(String query, int maxTokens, String projectKey) {
        return buildContextForQueryWithTrace(query, maxTokens, projectKey).context();
    }

    public MemoryContextResult buildContextForQueryWithTrace(String query, int maxTokens, String projectKey) {
        List<ScoredEntry> relevant = retrieveLongTermMatches(query, 10, projectKey);
        int queryTokenCount = MemoryQueryTokenizer.tokenize(query).size();
        if (relevant.isEmpty()) {
            return new MemoryContextResult("", new MemoryRetrievalTrace(
                    query == null ? 0 : query.length(), queryTokenCount, 0, maxTokens, 0, List.of(), List.of()));
        }

        StringBuilder context = new StringBuilder();
        context.append("## 相关长期记忆\n\n");

        int usedTokens = 0;
        List<MemoryRetrievalTrace.Selection> injected = new ArrayList<>();
        List<MemoryRetrievalTrace.Selection> omittedByBudget = new ArrayList<>();
        for (int index = 0; index < relevant.size(); index++) {
            ScoredEntry scoredEntry = relevant.get(index);
            MemoryEntry entry = scoredEntry.entry();
            double score = scoredEntry.score();
            if (usedTokens + entry.getTokenCount() > maxTokens) {
                for (int remaining = index; remaining < relevant.size(); remaining++) {
                    ScoredEntry omitted = relevant.get(remaining);
                    omittedByBudget.add(new MemoryRetrievalTrace.Selection(omitted.entry().getId(),
                            omitted.score(), omitted.entry().getTokenCount()));
                }
                break;
            }

            context.append("- [").append(entry.getType()).append("] ")
                    .append(entry.getContent()).append("\n");
            usedTokens += entry.getTokenCount();
            injected.add(new MemoryRetrievalTrace.Selection(entry.getId(), score, entry.getTokenCount()));
        }

        String renderedContext = injected.isEmpty() ? "" : context.append("\n").toString();
        return new MemoryContextResult(renderedContext, new MemoryRetrievalTrace(
                query == null ? 0 : query.length(), queryTokenCount, relevant.size(), maxTokens, usedTokens,
                injected, omittedByBudget));
    }

    private static Comparator<ScoredEntry> scoredEntryComparator() {
        return Comparator.comparingDouble(ScoredEntry::score).reversed()
                .thenComparing(entry -> entry.entry().getTimestamp(), Comparator.reverseOrder())
                .thenComparing(entry -> entry.entry().getId());
    }

    private record ScoredEntry(MemoryEntry entry, double score, boolean fromShortTerm) {}

    public record MemoryContextResult(String context, MemoryRetrievalTrace trace) {}
}
