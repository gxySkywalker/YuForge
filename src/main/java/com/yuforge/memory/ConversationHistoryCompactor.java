package com.yuforge.memory;

import com.yuforge.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 管理真正发送给 LLM 的 conversationHistory。
 *
 * <p>达到高水位后先把旧的大型 tool_result 归档为可恢复 artifact；如果释放空间后仍超过阈值，
 * 再按 user turn 边界保留近期完整事务，并把其余历史压缩为结构化 checkpoint。摘要采用全量
 * 分块 + Reduce，不再截断中间历史。</p>
 */
public class ConversationHistoryCompactor {

    private static final Logger log = LoggerFactory.getLogger(ConversationHistoryCompactor.class);

    private static final int DEFAULT_RETAIN_RECENT_ROUNDS = 3;
    private static final int KEEP_RECENT_TOOL_RESULTS = 3;
    private static final int MIN_ARCHIVE_TOOL_RESULT_TOKENS = 1_200;
    private static final int MAX_SUMMARY_CHUNK_CHARS = 45_000;
    private static final int TOOL_PREVIEW_CHARS = 360;

    private static final String SUMMARY_PROMPT = """
            请把下面的对话片段压缩成结构化工程检查点。必须使用以下 Markdown 标题；没有内容写“无”：
            ## 目标与用户约束
            ## 已完成操作与关键结果
            ## 代码与文件状态
            ## 重要工具产物
            ## 失败尝试与风险
            ## 未完成事项与下一步

            规则：
            1. 保留精确文件路径、符号名、命令、测试结果、错误原因、用户明确约束。
            2. 工具结果中出现 artifact_id 时必须原样保留，并说明何时需要 read_tool_artifact 恢复原文。
            3. 不复述闲聊，不虚构未发生的操作，不把计划写成已完成。
            4. 输出检查点本身，不要输出前言或解释。

            === 对话片段 ===
            %s
            === 对话片段结束 ===
            """;

    private static final String REDUCE_PROMPT = """
            请把下面多个结构化工程检查点合并成一个检查点。必须保留所有仍有效的用户约束、
            文件修改、关键工具结果、artifact_id、失败原因和未完成事项；后出现且明确更新的状态覆盖旧状态。
            输出仍使用这六个 Markdown 标题：目标与用户约束、已完成操作与关键结果、代码与文件状态、
            重要工具产物、失败尝试与风险、未完成事项与下一步。不要输出前言。

            === 待合并检查点 ===
            %s
            === 待合并检查点结束 ===
            """;

    private LlmClient llmClient;
    private final int retainRecentRounds;
    private final ToolResultArtifactStore artifactStore;

    public ConversationHistoryCompactor(LlmClient llmClient) {
        this(llmClient, DEFAULT_RETAIN_RECENT_ROUNDS, new ToolResultArtifactStore());
    }

    public ConversationHistoryCompactor(LlmClient llmClient, int retainRecentRounds) {
        this(llmClient, retainRecentRounds, new ToolResultArtifactStore());
    }

    public ConversationHistoryCompactor(LlmClient llmClient, ToolResultArtifactStore artifactStore) {
        this(llmClient, DEFAULT_RETAIN_RECENT_ROUNDS, artifactStore);
    }

    ConversationHistoryCompactor(LlmClient llmClient, int retainRecentRounds,
                                 ToolResultArtifactStore artifactStore) {
        this.llmClient = llmClient;
        this.retainRecentRounds = Math.max(1, retainRecentRounds);
        this.artifactStore = artifactStore == null ? new ToolResultArtifactStore() : artifactStore;
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public boolean compactIfNeeded(List<LlmClient.Message> history, int triggerTokens) {
        return manageIfNeeded(history, triggerTokens).compacted();
    }

    /** 高水位治理结果同时暴露归档和摘要两种动作，便于 CLI 与指标区分。 */
    public ContextManagementResult manageIfNeeded(List<LlmClient.Message> history, int triggerTokens) {
        int beforeTokens = TokenBudget.estimateMessagesTokens(history);
        if (history == null || history.isEmpty() || beforeTokens < triggerTokens) {
            return ContextManagementResult.unchanged(beforeTokens);
        }

        int archived = archiveOldLargeToolResults(history);
        int afterArchiveTokens = TokenBudget.estimateMessagesTokens(history);
        if (afterArchiveTokens < triggerTokens) {
            log.info("context editing archived {} tool results: tokens {} -> {}",
                    archived, beforeTokens, afterArchiveTokens);
            return new ContextManagementResult(false, archived, beforeTokens, afterArchiveTokens);
        }

        boolean compacted = compact(history, triggerTokens, false, retainRecentRounds);
        int afterTokens = TokenBudget.estimateMessagesTokens(history);
        return new ContextManagementResult(compacted, archived, beforeTokens, afterTokens);
    }

    /** 手工压缩保留最近一个 user turn，跳过阈值。 */
    public boolean compactNow(List<LlmClient.Message> history) {
        archiveOldLargeToolResults(history);
        return compact(history, 0, true, 1);
    }

    private boolean compact(List<LlmClient.Message> history, int triggerTokens, boolean force, int retainRounds) {
        if (history == null || history.isEmpty()) return false;
        int currentTokens = TokenBudget.estimateMessagesTokens(history);
        if (!force && currentTokens < triggerTokens) return false;

        int systemEnd = "system".equals(history.get(0).role()) ? 1 : 0;
        List<Integer> userIndices = new ArrayList<>();
        for (int i = systemEnd; i < history.size(); i++) {
            if ("user".equals(history.get(i).role())) userIndices.add(i);
        }
        int effectiveRetainRounds = Math.max(1, retainRounds);
        if (userIndices.size() <= effectiveRetainRounds) {
            log.info("compact skip: only {} user turns, retain {}", userIndices.size(), effectiveRetainRounds);
            return false;
        }

        int splitIdx = userIndices.get(userIndices.size() - effectiveRetainRounds);
        if (splitIdx <= systemEnd) return false;
        // 无论它是否属于“最近 3 个工具结果”，凡是即将进入摘要区的大结果都必须先归档，
        // 确保结构化 checkpoint 可以携带 artifact_id，而不是只能依赖有损摘要。
        archiveToolResultsInRange(history, systemEnd, splitIdx);
        List<LlmClient.Message> oldMessages = new ArrayList<>(history.subList(systemEnd, splitIdx));
        if (oldMessages.isEmpty()) return false;

        String summary;
        try {
            summary = summarize(oldMessages);
        } catch (IOException e) {
            log.warn("conversation checkpoint generation failed; keep protocol-safe history", e);
            return false;
        }
        if (summary == null || summary.isBlank()) {
            log.warn("conversation checkpoint returned empty; skip compaction");
            return false;
        }

        List<LlmClient.Message> rebuilt = new ArrayList<>();
        rebuilt.addAll(history.subList(0, systemEnd));
        rebuilt.add(LlmClient.Message.user("[已压缩的历史对话摘要 · 结构化检查点]\n" + summary.trim()));
        rebuilt.add(LlmClient.Message.assistant("好的，我已加载历史检查点和近期完整事务，请继续。"));
        rebuilt.addAll(history.subList(splitIdx, history.size()));

        int afterTokens = TokenBudget.estimateMessagesTokens(rebuilt);
        history.clear();
        history.addAll(rebuilt);
        log.info(String.format(Locale.ROOT,
                "compacted conversationHistory: tokens %d -> %d, messages %d -> %d, checkpoint chars %d",
                currentTokens, afterTokens, oldMessages.size(), rebuilt.size(), summary.length()));
        return true;
    }

    /**
     * 全量分块摘要。保留此方法为单测替换点；生产实现不会因固定字符上限丢弃中间历史。
     */
    protected String summarize(List<LlmClient.Message> messages) throws IOException {
        if (llmClient == null) throw new IOException("LLM client not configured");
        String serialized = serialize(messages);
        List<String> chunks = splitIntoChunks(serialized, MAX_SUMMARY_CHUNK_CHARS);
        List<String> checkpoints = new ArrayList<>();
        for (String chunk : chunks) {
            checkpoints.add(callSummaryModel(String.format(SUMMARY_PROMPT, chunk)));
        }
        return reduceCheckpoints(checkpoints);
    }

    private String reduceCheckpoints(List<String> checkpoints) throws IOException {
        if (checkpoints.isEmpty()) return "";
        if (checkpoints.size() == 1) return checkpoints.get(0);

        List<String> current = checkpoints;
        while (current.size() > 1) {
            List<String> next = new ArrayList<>();
            StringBuilder batch = new StringBuilder();
            for (String checkpoint : current) {
                String section = "\n--- CHECKPOINT ---\n" + checkpoint + "\n";
                if (!batch.isEmpty() && batch.length() + section.length() > MAX_SUMMARY_CHUNK_CHARS) {
                    next.add(callSummaryModel(String.format(REDUCE_PROMPT, batch)));
                    batch.setLength(0);
                }
                batch.append(section);
            }
            if (!batch.isEmpty()) next.add(callSummaryModel(String.format(REDUCE_PROMPT, batch)));
            if (next.size() == current.size()) {
                // 极端情况下每份摘要自身过大，仍要保证归并能够推进。
                String joined = String.join("\n--- CHECKPOINT ---\n", current);
                next = List.of(callSummaryModel(String.format(REDUCE_PROMPT, joined)));
            }
            current = next;
        }
        return current.get(0);
    }

    private String callSummaryModel(String prompt) throws IOException {
        List<LlmClient.Message> request = List.of(
                LlmClient.Message.system("你是代码 Agent 的上下文压缩器，只输出忠实、结构化的工程检查点。"),
                LlmClient.Message.user(prompt)
        );
        LlmClient.ChatResponse response = llmClient.chat(request, null);
        return response == null || response.content() == null ? "" : response.content().trim();
    }

    private int archiveOldLargeToolResults(List<LlmClient.Message> history) {
        if (history == null || history.isEmpty()) return 0;
        List<Integer> toolIndices = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            if ("tool".equals(history.get(i).role())) toolIndices.add(i);
        }
        int archiveBefore = Math.max(0, toolIndices.size() - KEEP_RECENT_TOOL_RESULTS);
        if (archiveBefore == 0) return 0;
        return archiveToolResultsAtIndices(history, toolIndices.subList(0, archiveBefore));
    }

    private int archiveToolResultsInRange(List<LlmClient.Message> history, int fromInclusive, int toExclusive) {
        List<Integer> indices = new ArrayList<>();
        for (int i = Math.max(0, fromInclusive); i < Math.min(history.size(), toExclusive); i++) {
            if ("tool".equals(history.get(i).role())) indices.add(i);
        }
        return archiveToolResultsAtIndices(history, indices);
    }

    private int archiveToolResultsAtIndices(List<LlmClient.Message> history, List<Integer> indices) {
        Map<String, String> toolNamesByCallId = toolNamesByCallId(history);
        int archived = 0;
        for (int index : indices) {
            LlmClient.Message message = history.get(index);
            String content = message.content() == null ? "" : message.content();
            if (content.startsWith("[旧工具结果已归档]")
                    || TokenBudget.estimateTextTokens(content) < MIN_ARCHIVE_TOOL_RESULT_TOKENS) {
                continue;
            }
            String toolName = toolNamesByCallId.getOrDefault(message.toolCallId(), "unknown");
            ToolResultArtifactStore.Artifact artifact = artifactStore.archive(toolName, message.toolCallId(), content);
            history.set(index, new LlmClient.Message(
                    "tool", archivePlaceholder(artifact), null, null, message.toolCallId()));
            archived++;
        }
        return archived;
    }

    private static Map<String, String> toolNamesByCallId(List<LlmClient.Message> history) {
        Map<String, String> names = new HashMap<>();
        for (LlmClient.Message message : history) {
            if (message.toolCalls() == null) continue;
            for (LlmClient.ToolCall call : message.toolCalls()) {
                if (call != null && call.id() != null && call.function() != null) {
                    names.put(call.id(), call.function().name());
                }
            }
        }
        return names;
    }

    private static String archivePlaceholder(ToolResultArtifactStore.Artifact artifact) {
        String compact = artifact.content().replaceAll("\\s+", " ").trim();
        String preview = compact.substring(0, Math.min(TOOL_PREVIEW_CHARS, compact.length()));
        if (compact.length() > TOOL_PREVIEW_CHARS) preview += "...";
        return "[旧工具结果已归档]\n"
                + "artifact_id: " + artifact.id() + "\n"
                + "tool: " + artifact.toolName() + "\n"
                + "original_tokens: " + artifact.tokenCount() + "\n"
                + "preview: " + preview + "\n"
                + "需要精确原文时调用 read_tool_artifact。";
    }

    private static String serialize(List<LlmClient.Message> messages) {
        StringBuilder builder = new StringBuilder();
        for (LlmClient.Message message : messages) {
            builder.append(message.role().toUpperCase(Locale.ROOT)).append(": ");
            if (message.content() != null) builder.append(message.content());
            if (message.toolCalls() != null) {
                for (LlmClient.ToolCall call : message.toolCalls()) {
                    builder.append("\n  TOOL_CALL id=").append(call.id())
                            .append(" name=").append(call.function().name())
                            .append(" args=").append(call.function().arguments());
                }
            }
            builder.append("\n\n");
        }
        return builder.toString();
    }

    private static List<String> splitIntoChunks(String text, int maxChars) {
        if (text == null || text.isEmpty()) return List.of("");
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + maxChars);
            if (end < text.length()) {
                int boundary = text.lastIndexOf("\n\n", end);
                if (boundary > start + maxChars / 2) end = boundary + 2;
            }
            chunks.add(text.substring(start, end));
            start = end;
        }
        return chunks;
    }

    public int retainRecentRounds() {
        return retainRecentRounds;
    }

    public ToolResultArtifactStore artifactStore() {
        return artifactStore;
    }

    public record ContextManagementResult(boolean compacted, int archivedToolResults,
                                          int beforeTokens, int afterTokens) {
        static ContextManagementResult unchanged(int tokens) {
            return new ContextManagementResult(false, 0, tokens, tokens);
        }

        public boolean changed() {
            return compacted || archivedToolResults > 0;
        }
    }
}
