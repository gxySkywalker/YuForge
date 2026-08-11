package com.yuforge.memory;

import com.yuforge.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上下文治理（工具结果无损归档 + 结构化检查点压缩）的量化 benchmark。
 *
 * <p>不是性能测试，而是 token 节省测量：构造真实形状的长对话，用生产代码路径
 * {@link ConversationHistoryCompactor#manageIfNeeded} 治理，比较治理前后的上下文占用；
 * 并模拟同一长任务在「无压缩 / 有压缩」两世界下的行为差异。所有 token 数均来自
 * {@link TokenBudget#estimateMessagesTokens} 同一估算器，保证口径一致。</p>
 *
 * <p>测量假设（在输出中如实标注）：累计模拟按「每轮重发全部历史、每轮 1 次主模型调用」
 * 简化建模；真实 ReAct 每轮多次调用，但不影响两世界对比的方向。</p>
 */
class ContextCompressionBenchmarkTest {

    private static final String SYSTEM_PROMPT =
            "You are YuForge, an autonomous coding agent.\n"
            + "Plan the task, execute tools, verify results, and report.\n"
            + "Available tools: read_file, write_file, grep_code, glob_files, run_command, search_code.\n";

    private static final int WINDOW = 131_072; // 128K 上下文窗口
    private static final int TRIGGER =
            (int) (new TokenBudget(WINDOW).getAvailableForConversation() * 0.9);

    // ------------------------------------------------------------------
    // 场景 A：工具结果密集（典型编码会话，read_file / grep 大结果）
    // ------------------------------------------------------------------

    @Test
    void toolHeavySessionArchivesBulkResults() {
        List<LlmClient.Message> history = buildToolHeavySession(80, 7_000);
        int before = TokenBudget.estimateMessagesTokens(history);

        ConversationHistoryCompactor.ContextManagementResult result =
                new ConversationHistoryCompactor(null, 3).manageIfNeeded(history, TRIGGER);

        int after = TokenBudget.estimateMessagesTokens(history);
        System.out.printf("""
                        [场景A] 工具结果密集会话（80 轮，大文件读取/grep）
                          治理前: %8d tokens
                          治理后: %8d tokens  (归档 %d 个大型工具结果，artifact 无损可恢复)
                          降幅:    %.1f%%
                        """,
                before, after, result.archivedToolResults(),
                pct(before, after));

        assertTrue(before > TRIGGER, "构造的历史应超过触发阈值");
        assertTrue(after < TRIGGER, "归档后应回落到阈值内");
        assertTrue(after < before / 2, "归档应释放一半以上上下文");
        assertTrue(result.archivedToolResults() >= 70, "大部分大型工具结果应被归档");
    }

    // ------------------------------------------------------------------
    // 场景 B：分析对话密集（无大型工具结果可归档，走检查点压缩）
    // ------------------------------------------------------------------

    @Test
    void textHeavySessionCheckpointsOldRounds() {
        List<LlmClient.Message> history = buildTextHeavySession(60, 4_000);
        int before = TokenBudget.estimateMessagesTokens(history);

        ConversationHistoryCompactor compactor = new CheckpointingCompactor();
        ConversationHistoryCompactor.ContextManagementResult result =
                compactor.manageIfNeeded(history, TRIGGER);

        int after = TokenBudget.estimateMessagesTokens(history);
        System.out.printf("""
                        [场景B] 分析对话密集会话（60 轮，无可归档大结果）
                          治理前: %8d tokens
                          治理后: %8d tokens  (结构化检查点 + 保留最近 3 轮)
                          降幅:    %.1f%%
                        """,
                before, after, pct(before, after));

        assertTrue(before > TRIGGER, "构造的历史应超过触发阈值");
        assertTrue(result.compacted(), "文本主导会话应触发检查点压缩");
        assertTrue(after < before / 8, "压缩应释放 85% 以上上下文");
        assertEquals(1, ((CheckpointingCompactor) compactor).summarizeCalls);
    }

    // ------------------------------------------------------------------
    // 累计模拟：同一 80 轮任务，无压缩 vs 有压缩
    // ------------------------------------------------------------------

    @Test
    void cumulativeLongTaskSurvivesWithoutOverflow() {
        // 每轮 4 条消息（user / assistant(tool_call) / tool / assistant），80 轮 = 320 条
        List<LlmClient.Message> messages = buildToolHeavyTurns(80, 7_000);
        int perTurn = 4;
        int turnCount = messages.size() / perTurn;

        // 世界一：无压缩 —— 历史无界增长
        List<LlmClient.Message> hNo = new ArrayList<>(List.of(LlmClient.Message.system(SYSTEM_PROMPT)));
        long cumulativeNo = 0;
        int diedAt = -1;
        for (int t = 0; t < turnCount; t++) {
            int input = TokenBudget.estimateMessagesTokens(hNo);
            if (input > WINDOW) { diedAt = t + 1; break; }
            cumulativeNo += input;
            appendTurn(hNo, messages, t, perTurn);
        }

        // 世界二：有压缩 —— 超阈值即治理
        ConversationHistoryCompactor compactor = new ConversationHistoryCompactor(null, 3);
        List<LlmClient.Message> hYes = new ArrayList<>(List.of(LlmClient.Message.system(SYSTEM_PROMPT)));
        long cumulativeYes = 0;
        int managements = 0;
        int peakBefore = 0;
        int peakAfter = 0;
        for (int t = 0; t < turnCount; t++) {
            int est = TokenBudget.estimateMessagesTokens(hYes);
            peakBefore = Math.max(peakBefore, est);
            cumulativeYes += est;
            appendTurn(hYes, messages, t, perTurn);
            if (TokenBudget.estimateMessagesTokens(hYes) > TRIGGER) {
                compactor.manageIfNeeded(hYes, TRIGGER);
                managements++;
                peakAfter = Math.max(peakAfter, TokenBudget.estimateMessagesTokens(hYes));
            }
        }

        // 世界二延长到 500 轮，验证有界性（归档优先，必要时结构化检查点）
        ConversationHistoryCompactor longCompactor = new CheckpointingCompactor();
        List<LlmClient.Message> hLong = new ArrayList<>(List.of(LlmClient.Message.system(SYSTEM_PROMPT)));
        int longManagements = 0;
        int longPeakBefore = 0;
        int longPeakAfter = 0;
        for (int t = 0; t < 500; t++) {
            longPeakBefore = Math.max(longPeakBefore, TokenBudget.estimateMessagesTokens(hLong));
            appendTurn(hLong, messages, t % turnCount, perTurn);
            if (TokenBudget.estimateMessagesTokens(hLong) > TRIGGER) {
                longCompactor.manageIfNeeded(hLong, TRIGGER);
                longManagements++;
                longPeakAfter = Math.max(longPeakAfter, TokenBudget.estimateMessagesTokens(hLong));
            }
        }

        System.out.printf("""
                        [累计模拟] 80 轮工具密集任务，每轮重发全部历史（简化：每轮 1 次主模型调用）
                          无压缩: 累计输入 %12d tokens → 第 %d 轮超出 %d 窗口，任务中断
                          有压缩: 累计输入 %12d tokens → %d 轮完整执行；压缩 %d 次，
                                  治理触发点单次输入峰值 %d，治理后回落到 %d（-%.0f%%）
                          延长至 500 轮: 全程未超窗（治理前峰值 %d < 窗口 %d），治理 %d 次，历史保持有界
                        """,
                cumulativeNo, diedAt, WINDOW,
                cumulativeYes, turnCount, managements, peakBefore, peakAfter, pct(peakBefore, peakAfter),
                longPeakBefore, WINDOW, longManagements);

        assertTrue(diedAt > 0, "无压缩应在窗口内某轮超窗中断");
        assertTrue(diedAt < 80, "无压缩应在完成前超窗（第 %d 轮）".formatted(diedAt));
        assertTrue(diedAt >= 50, "无压缩至少能撑到 ~50 轮");
        assertTrue(managements > 0, "有压缩应至少触发一次治理");
        assertTrue(peakAfter > 0 && peakAfter < TRIGGER, "有压缩治理后应回落到阈值内");
        assertTrue(longPeakBefore < WINDOW, "500 轮治理前峰值也不应超窗");
        assertTrue(longPeakAfter < TRIGGER, "500 轮治理后应保持有界");
    }

    // ------------------------------------------------------------------
    // 构造器
    // ------------------------------------------------------------------

    /** 工具结果密集会话：每轮 = 用户指令 + read_file 调用 + 大工具结果 + 回复。 */
    private static List<LlmClient.Message> buildToolHeavySession(int turns, int toolChars) {
        return new ArrayList<>(appendTurns(
                new ArrayList<>(List.of(LlmClient.Message.system(SYSTEM_PROMPT))),
                buildToolHeavyTurns(turns, toolChars)));
    }

    private static List<LlmClient.Message> buildToolHeavyTurns(int turns, int toolChars) {
        List<LlmClient.Message> result = new ArrayList<>();
        for (int i = 1; i <= turns; i++) {
            String callId = "call-" + i;
            result.add(LlmClient.Message.user("第 %d 轮: 请检查 src/main/java/com/yuforge/service/%s 并修复发现的 bug，"
                    .formatted(i, "Module" + i) + "保持与现有代码风格一致，完成后运行相关测试确认。"));
            result.add(LlmClient.Message.assistant(null, null, List.of(
                    new LlmClient.ToolCall(callId,
                            new LlmClient.ToolCall.Function(i % 3 == 0 ? "grep_code" : "read_file",
                                    "{\"path\":\"src/main/java/com/yuforge/service/Module" + i + ".java\"}")))));
            result.add(LlmClient.Message.tool(callId, sourceCode(toolChars)));
            result.add(LlmClient.Message.assistant("Found %d issues in module %d: " +
                    "null-check missing, error swallowed. Fixed and verified via unit tests."
                    .formatted(i % 3, i)));
        }
        return result;
    }

    /** 分析对话密集会话：每轮 = 长用户分析 + 小工具结果 + 长回复，工具结果 < 归档阈值。 */
    private static List<LlmClient.Message> buildTextHeavySession(int turns, int textChars) {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system(SYSTEM_PROMPT));
        for (int i = 1; i <= turns; i++) {
            String callId = "call-" + i;
            history.add(LlmClient.Message.user("分析第 %d 个问题: " + longAscii(textChars)));
            history.add(LlmClient.Message.assistant(null, null, List.of(
                    new LlmClient.ToolCall(callId,
                            new LlmClient.ToolCall.Function("grep_code", "{\"pattern\":\"TODO-" + i + "\"}")))));
            history.add(LlmClient.Message.tool(callId, "src/main/java/com/yuforge/Main.java:12: TODO-" + i));
            history.add(LlmClient.Message.assistant(longAscii(textChars)));
        }
        return history;
    }

    private static List<LlmClient.Message> appendTurns(List<LlmClient.Message> history,
                                                       List<LlmClient.Message> turns) {
        for (LlmClient.Message message : turns) history.add(message);
        return history;
    }

    private static void appendTurn(List<LlmClient.Message> history,
                                   List<LlmClient.Message> messages, int turnIndex, int perTurn) {
        for (int m = 0; m < perTurn; m++) {
            history.add(messages.get(turnIndex * perTurn + m));
        }
    }

    /** 模拟一个工具密集会话的真实形状：一段接一段的 Java 源码。 */
    private static String sourceCode(int chars) {
        String unit =
                "package com.example.service;\n"
                + "import java.util.List; import java.util.ArrayList;\n"
                + "public final class ModuleLoader {\n"
                + "    private final List<String> modules = new ArrayList<>();\n"
                + "    public synchronized void register(String name) {\n"
                + "        if (name == null || name.isBlank()) throw new IllegalArgumentException(\"name\");\n"
                + "        modules.add(name);\n"
                + "    }\n"
                + "    public List<String> loaded() { return List.copyOf(modules); }\n"
                + "}\n";
        StringBuilder sb = new StringBuilder(chars);
        while (sb.length() < chars) {
            sb.append(unit).append("// ---- chunk ").append(sb.length()).append(" ----\n");
        }
        return sb.toString();
    }

    private static String longAscii(int chars) {
        StringBuilder sb = new StringBuilder(chars);
        String seed = "This analysis covers concurrency, error handling, and data consistency across the "
                + "module boundaries. The key tradeoff is between throughput and correctness; ";
        while (sb.length() < chars) sb.append(seed);
        return sb.toString();
    }

    private static double pct(int before, int after) {
        return 100.0 * (before - after) / before;
    }

    /** 检查点压缩用 stub：summarize 返回固定结构化检查点，避免真实 LLM 依赖。 */
    private static final class CheckpointingCompactor extends ConversationHistoryCompactor {
        int summarizeCalls;

        CheckpointingCompactor() {
            super(null, 3);
        }

        @Override
        protected String summarize(List<LlmClient.Message> messages) {
            summarizeCalls++;
            return """
                    ## 目标与用户约束
                    修复模块加载器并发与空值校验问题，保持现有 API 不变。
                    ## 已完成操作与关键结果
                    定位 ModuleLoader.register 空值校验缺失；修复并发下 List 竞争。
                    ## 代码与文件状态
                    src/main/java/com/yuforge/service/ModuleLoader.java 已改，测试通过。
                    ## 重要工具产物
                    无 artifact_id。
                    ## 失败尝试与风险
                    曾尝试 CopyOnWriteArrayList，因内存开销放弃。
                    ## 未完成事项与下一步
                    补充压力测试验证并发安全。""";
        }
    }
}
