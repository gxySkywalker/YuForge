package com.yuforge.tool;

import com.yuforge.llm.LlmClient;
import com.yuforge.memory.TokenBudget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/**
 * 测量内置工具定义的固定上下文开销：getToolDefinitions() 全量进每轮请求，
 * 衡量「26 个工具是不是太多」的 token 代价。
 */
class ToolDefinitionOverheadTest {

    private static final Set<String> CORE_LOOP = Set.of(
            "read_file", "write_file", "apply_patch", "list_dir",
            "glob_files", "grep_code", "execute_command");

    @Test
    void measureToolDefinitionOverhead() {
        ToolRegistry registry = new ToolRegistry();
        List<LlmClient.Tool> defs = registry.getToolDefinitions();

        int coreTotal = 0;
        int peripheralTotal = 0;
        int peripheralCount = 0;
        for (LlmClient.Tool t : defs) {
            int cost = TokenBudget.estimateTextTokens(t.name())
                    + TokenBudget.estimateTextTokens(t.description())
                    + TokenBudget.estimateTextTokens(t.parameters() == null ? "" : t.parameters().toString())
                    + 8;
            if (CORE_LOOP.contains(t.name())) {
                coreTotal += cost;
            } else {
                peripheralCount++;
                peripheralTotal += cost;
            }
        }
        int total = TokenBudget.estimateToolDefinitionTokens(defs);

        System.out.printf("工具定义总 token: %d（%d 个工具）%n", total, defs.size());
        System.out.printf("核心循环 %d 个工具: %d tokens%n", CORE_LOOP.size(), coreTotal);
        System.out.printf("外围 %d 个工具: %d tokens%n", peripheralCount, peripheralTotal);
        System.out.printf("占 128K 窗口: %.1f%%（每轮固定开销）%n", 100.0 * total / 131_072);
        System.out.printf("Prompt Cache 场景（稳定前缀缓存）: 每次冷启动付 %d，命中后每轮仅付增量 %n", total);
    }
}
