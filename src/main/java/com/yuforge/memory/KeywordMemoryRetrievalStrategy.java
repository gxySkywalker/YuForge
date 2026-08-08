package com.yuforge.memory;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 默认的零依赖关键词检索策略，保证离线可用与确定性排序。 */
final class KeywordMemoryRetrievalStrategy implements MemoryRetrievalStrategy {
    @Override
    public List<Match> rank(Collection<MemoryEntry> entries, String query, int limit) {
        if (entries == null || query == null || query.isBlank() || limit <= 0) return List.of();
        return entries.stream()
                .map(entry -> new Match(entry, score(entry, query)))
                .filter(match -> match.score() > 0)
                .sorted(Comparator.comparingDouble(Match::score).reversed()
                        .thenComparing(match -> match.entry().getTimestamp(), Comparator.reverseOrder())
                        .thenComparing(match -> match.entry().getId()))
                .limit(limit)
                .toList();
    }

    private double score(MemoryEntry entry, String query) {
        if (entry == null) return 0;
        String content = entry.getContent() == null ? "" : entry.getContent().toLowerCase(Locale.ROOT);
        String normalizedQuery = query.toLowerCase(Locale.ROOT).trim();
        if (content.contains(normalizedQuery)) return 1.0;

        Set<String> queryWords = MemoryQueryTokenizer.tokenize(normalizedQuery);
        if (queryWords.isEmpty()) return 0;
        long matchedWords = queryWords.stream().filter(content::contains).count();
        if (matchedWords == 0) return 0;

        long ageMs = System.currentTimeMillis() - entry.getTimestamp().toEpochMilli();
        double ageHours = ageMs / (1000.0 * 60 * 60);
        double timeDecay = Math.max(0.5, 1.0 - ageHours / 24.0);
        boolean metadataMatches = entry.getMetadata().values().stream()
                .filter(Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> queryWords.stream().anyMatch(value::contains));
        return Math.min(1.0, (double) matchedWords / queryWords.size() * timeDecay + (metadataMatches ? 0.08 : 0));
    }
}
