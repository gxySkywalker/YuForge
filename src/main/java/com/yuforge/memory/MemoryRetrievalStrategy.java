package com.yuforge.memory;

import java.util.Collection;
import java.util.List;

/**
 * 长期/短期记忆的排序策略边界。
 *
 * 当前默认实现是本地关键词策略；未来 Hybrid 策略可在此接口后合并关键词与 embedding
 * 的候选，失败时回退到 KeywordMemoryRetrievalStrategy，不改变调用方或注入协议。
 */
public interface MemoryRetrievalStrategy {
    List<Match> rank(Collection<MemoryEntry> entries, String query, int limit);

    record Match(MemoryEntry entry, double score) {}
}
