package com.yuforge.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuforge.llm.LlmClient;
import com.yuforge.memory.MemoryManager;
import com.yuforge.render.Renderer;
import com.yuforge.runtime.CancellationContext;
import com.yuforge.tool.ToolRegistry;
import com.yuforge.util.AnsiStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Agent 编排器 - Multi-Agent 系统的"主"
 *
 * 负责管理团队、分配任务、路由消息、解决冲突。
 * 采用主从架构：编排器是主，子代理是从。
 *
 * 协作流程：
 * 1. 用户提交任务 -> 编排器交给规划者
 * 2. 规划者拆解任务 -> 编排器解析计划
 * 3. 编排器按依赖顺序将子任务分配给执行者
 * 4. 执行者返回结果 -> 编排器交给检查者
 * 5. 检查者通过则完成，否则带上反馈重新分配给执行者
 * 6. 所有子任务完成后，编排器汇总返回最终结果
 *
 * 并行策略：
 * - 同一依赖批次内部 **并行** 执行（最多 Worker 池大小并发，默认 2）
 * - 每个并行步骤使用独立的 PrintStream 缓冲流式输出，批次结束后按 step_id 顺序 flush 到 stdout，
 *   避免多线程写同一个终端流造成交错，同时仍让用户看到结构化的执行过程
 * - 单步批次仍走直连流式路径，保持"实时打字"的观感
 * - Worker 通过 {@link java.util.concurrent.BlockingQueue} 池化分配，确保同一 Worker 不会被两个步骤并发占用
 * - Reviewer 在并行路径中按步骤即时创建独立实例，避免对话历史竞争
 */
public class AgentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RETRIES_PER_STEP = 2;

    private final LlmClient llmClient;
    private final SubAgent planner;
    private final List<SubAgent> workers;
    private final SubAgent reviewer;
    private final MemoryManager memoryManager;
    private final ToolRegistry toolRegistry;
    private final PrintStream out;
    private final Renderer renderer;
    private Supplier<String> externalContextSupplier = () -> "";

    // 执行步骤的数据结构（package-private 供测试访问）
    record ExecutionStep(String id, String description, String type,
                                  List<String> dependencies, String result,
                                  StepStatus status) {
        static ExecutionStep pending(String id, String description, String type, List<String> dependencies) {
            return new ExecutionStep(id, description, type, dependencies, null, StepStatus.PENDING);
        }

        ExecutionStep withResult(String result) {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.COMPLETED);
        }

        ExecutionStep withFailed(String result) {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.FAILED);
        }

        ExecutionStep started() {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.RUNNING);
        }
    }

    enum StepStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }

    public AgentOrchestrator(LlmClient llmClient) {
        this(llmClient, new ToolRegistry(), new MemoryManager(llmClient));
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, new MemoryManager(llmClient));
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry, MemoryManager memoryManager) {
        this(llmClient, toolRegistry, memoryManager, System.out);
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry,
                             MemoryManager memoryManager, PrintStream out) {
        this(llmClient, toolRegistry, memoryManager, out, null);
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry,
                             MemoryManager memoryManager, Renderer renderer) {
        this(llmClient, toolRegistry, memoryManager,
                renderer == null ? null : renderer.stream(), renderer);
    }

    private AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry,
                              MemoryManager memoryManager, PrintStream out, Renderer renderer) {
        this.llmClient = llmClient;
        this.out = out == null ? System.out : out;
        this.renderer = renderer;
        this.toolRegistry = toolRegistry;
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
        memoryManager.setProjectPath(this.toolRegistry.getProjectPath());
        this.toolRegistry.setScopedMemorySaver(memoryManager::storeFact);
        this.planner = new SubAgent("planner", AgentRole.PLANNER, llmClient, toolRegistry);
        this.workers = List.of(
                new SubAgent("worker-1", AgentRole.WORKER, llmClient, toolRegistry),
                new SubAgent("worker-2", AgentRole.WORKER, llmClient, toolRegistry)
        );
        this.workers.forEach(worker -> worker.setShowWorkerTranscript(false));
        this.reviewer = new SubAgent("reviewer", AgentRole.REVIEWER, llmClient, toolRegistry);
        if (renderer != null) {
            this.planner.setRenderer(renderer);
            this.workers.forEach(worker -> worker.setRenderer(renderer));
            this.reviewer.setRenderer(renderer);
        }
        this.memoryManager = memoryManager;
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
        planner.setExternalContextSupplier(this.externalContextSupplier);
        workers.forEach(worker -> worker.setExternalContextSupplier(this.externalContextSupplier));
        reviewer.setExternalContextSupplier(this.externalContextSupplier);
    }

    /**
     * 把 Skill 系统下发给所有 SubAgent。Multi-Agent 三个角色共享同一 SkillRegistry（索引一致），
     * 但共享同一 SkillContextBuffer——简化实现，避免角色级 buffer 隔离的工程开销。
     * 任务书 §3.6 描述的"角色独立 buffer"作为可观察的优化项暂未启用。
     */
    public void setSkillSystem(com.yuforge.skill.SkillRegistry skillRegistry,
                               com.yuforge.skill.SkillContextBuffer skillContextBuffer) {
        planner.setSkillRegistry(skillRegistry);
        planner.setSkillContextBuffer(skillContextBuffer);
        for (SubAgent worker : workers) {
            worker.setSkillRegistry(skillRegistry);
            worker.setSkillContextBuffer(skillContextBuffer);
        }
        reviewer.setSkillRegistry(skillRegistry);
        reviewer.setSkillContextBuffer(skillContextBuffer);
    }

    /**
     * 运行多 Agent 协作任务
     */
    public String run(String userInput) {
        log.info("Multi-Agent run started: inputLength={}", userInput == null ? 0 : userInput.length());
        if (renderer != null) {
            // Main 为首个模型请求建立的 Thinking 活动态到此结束；Team 后续由编排器拥有活动行。
            renderer.endThinking();
        }
        toolRegistry.setMemoryWriteAuthorization(userInput);
        memoryManager.addUserMessage(userInput);
        if (CancellationContext.isCancelled()) {
            return "已取消当前多 Agent 任务。";
        }

        // 1. 规划阶段：让规划者拆解任务
        out.println(AnsiStyle.heading("Multi-Agent 协作"));
        out.println(AnsiStyle.status("  规划者 · 规划中"));

        AgentMessage planMessage = AgentMessage.task("orchestrator",
                "请为以下任务制定执行计划：\n" + userInput);
        AgentMessage planResult = planner.execute(planMessage, out);
        if (CancellationContext.isCancelled()) {
            planner.clearHistory();
            return "已取消当前多 Agent 任务。";
        }

        if (planResult.type() == AgentMessage.Type.ERROR) {
            planner.clearHistory();
            return "规划阶段失败：" + planResult.content();
        }
        if (planResult.content() == null || planResult.content().isBlank()) {
            planner.clearHistory();
            return "规划失败：Planner 未能生成有效计划";
        }

        // 2. 解析计划
        List<ExecutionStep> steps = parsePlan(planResult.content());
        if (steps.isEmpty()) {
            log.warn("Planner returned invalid plan, requesting one structured repair; preview={}",
                    preview(planResult.content(), 500));
            out.println(AnsiStyle.warning("  规划者 · 正在修复计划格式"));
            AgentMessage repaired = planner.execute(AgentMessage.task("orchestrator", """
                    你上一轮没有输出可解析的计划 JSON。现在不要调用工具、不要解释、不要输出 Markdown，
                    只根据已经获得的信息重新输出包含非空 steps 数组的合法 JSON。
                    """), out);
            if (repaired.type() != AgentMessage.Type.ERROR) {
                steps = parsePlan(repaired.content());
            }
            if (steps.isEmpty()) {
                planner.clearHistory();
                return "规划失败：模型连续两次未返回合法执行计划。请重试或切换模型。";
            }
        }
        planner.clearHistory();

        out.println(AnsiStyle.status("  规划者 · 就绪 · " + steps.size() + " 步"));
        out.println(summarizeSteps(steps));

        // 3. 执行阶段：按依赖顺序分配给执行者
        out.println(AnsiStyle.status("  执行阶段 · 开始"));
        Map<String, Integer> retryCount = new ConcurrentHashMap<>();
        int singleStepCursor = 0;
        int batchIndex = 0;

        while (true) {
            if (CancellationContext.isCancelled()) {
                return "已取消当前多 Agent 任务。";
            }
            List<ExecutionStep> executable = getExecutableSteps(steps);
            if (executable.isEmpty()) {
                break;
            }
            batchIndex++;

            if (executable.size() == 1) {
                // 单步批次：直接串行流式输出，保持实时打字观感
                ExecutionStep step = executable.get(0);
                SubAgent worker = workers.get(singleStepCursor % workers.size());
                singleStepCursor++;
                String context = buildStepContext(steps, step);
                runStep(step, steps, retryCount, worker, reviewer, context, out);
                worker.clearHistory();
            } else {
                // 多步批次：真正并行执行，每步用独立的 PrintStream 缓冲，完成后按 step_id 顺序 flush
                out.println(AnsiStyle.status("  执行者 · 并行批次 " + batchIndex + " · " + executable.size()
                        + " 步 · 最大并发 " + workers.size()));
                runBatchParallel(executable, steps, retryCount);
            }
        }

        // 5. 处理因前置失败而无法执行的残留步骤（显式提示用户）
        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.PENDING) {
                out.println(AnsiStyle.warning("  已跳过 [" + step.id() + "] · 前置步骤失败 · " + step.description()));
            }
        }

        // 6. 汇总结果
        String finalResult = buildFinalResult(steps);
        memoryManager.addAssistantMessage("[多Agent结果] " + finalResult);

        return finalResult;
    }

    /**
     * 解析规划者输出的 JSON 计划
     */
    List<ExecutionStep> parsePlan(String planJson) {
        try {
            String cleaned = extractFirstJsonObject(planJson);

            JsonNode root = mapper.readTree(cleaned);
            JsonNode stepsNode = root.path("steps");

            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                // 尝试 "tasks" 字段（兼容 Plan-and-Execute 的格式）
                stepsNode = root.path("tasks");
            }

            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                log.warn("Plan JSON has no 'steps' or 'tasks' array");
                return List.of();
            }

            List<ExecutionStep> steps = new ArrayList<>();
            Map<String, String> idMapping = new HashMap<>();
            int stepIndex = 1;

            // 第一遍：创建步骤（重编号）
            for (JsonNode stepNode : stepsNode) {
                String originalId = stepNode.path("id").asText();
                String newId = "step_" + stepIndex++;
                idMapping.put(originalId, newId);

                String description = stepNode.path("description").asText();
                String type = stepNode.path("type").asText("COMMAND");
                steps.add(ExecutionStep.pending(newId, description, type, new ArrayList<>()));
            }

            // 第二遍：建立依赖
            stepIndex = 1;
            for (JsonNode stepNode : stepsNode) {
                String newId = "step_" + stepIndex++;
                JsonNode depsNode = stepNode.path("dependencies");
                if (depsNode.isArray()) {
                    List<String> deps = new ArrayList<>();
                    for (JsonNode dep : depsNode) {
                        String mapped = idMapping.getOrDefault(dep.asText(), dep.asText());
                        deps.add(mapped);
                    }
                    // 替换步骤的依赖
                    int idx = stepIndex - 2;
                    if (idx >= 0 && idx < steps.size()) {
                        ExecutionStep old = steps.get(idx);
                        steps.set(idx, new ExecutionStep(old.id(), old.description(), old.type(),
                                deps, old.result(), old.status()));
                    }
                }
            }

            return steps;
        } catch (Exception e) {
            log.error("Failed to parse plan JSON", e);
            return List.of();
        }
    }

    private static String extractFirstJsonObject(String text) {
        if (text == null) {
            return "";
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return text.trim();
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return text.substring(start, i + 1);
            }
        }
        return text.substring(start).trim();
    }

    private static String preview(String text, int maxChars) {
        if (text == null) return "";
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= maxChars ? compact : compact.substring(0, maxChars) + "...";
    }

    /**
     * 获取当前可执行的步骤（依赖已全部完成）
     */
    List<ExecutionStep> getExecutableSteps(List<ExecutionStep> steps) {
        Map<String, StepStatus> statusMap = new HashMap<>();
        for (ExecutionStep step : steps) {
            statusMap.put(step.id(), step.status());
        }

        return steps.stream()
                .filter(step -> step.status() == StepStatus.PENDING)
                .filter(step -> step.dependencies().stream()
                        .allMatch(dep -> statusMap.get(dep) == StepStatus.COMPLETED))
                .toList();
    }

    /**
     * 解析检查者的审批结果
     *
     * 解析失败时采取保守策略：默认判为"不通过"，避免在审查者异常输出时让问题结果直接放行。
     */
    boolean parseReviewApproval(String reviewContent) {
        if (reviewContent == null || reviewContent.isEmpty()) {
            log.warn("Reviewer returned empty content, defaulting to rejected");
            return false;
        }
        try {
            String cleaned = reviewContent.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            JsonNode root = mapper.readTree(cleaned);
            JsonNode approvedNode = root.path("approved");
            if (approvedNode.isMissingNode() || approvedNode.isNull()) {
                log.warn("Reviewer JSON missing 'approved' field, defaulting to rejected");
                return false;
            }
            return approvedNode.asBoolean(false);
        } catch (Exception e) {
            // 无法解析 JSON：必须同时不含否定关键词且含有肯定关键词，才视为通过
            String lower = reviewContent.toLowerCase();
            boolean hasNegativeKeyword = lower.contains("未通过") || lower.contains("不通过")
                    || lower.contains("不合格") || lower.contains("有问题")
                    || lower.contains("\"approved\": false") || lower.contains("\"approved\":false");
            boolean hasPositiveKeyword = lower.contains("通过") || lower.contains("合格")
                    || lower.contains("\"approved\": true") || lower.contains("\"approved\":true");
            if (hasNegativeKeyword) {
                return false;
            }
            if (!hasPositiveKeyword) {
                log.warn("Reviewer output unparseable and contains no explicit approval, defaulting to rejected");
                return false;
            }
            return true;
        }
    }

    /**
     * 解析检查者反馈的问题
     */
    String parseReviewIssues(String reviewContent) {
        if (reviewContent == null || reviewContent.isEmpty()) {
            return "";
        }
        try {
            String cleaned = reviewContent.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            JsonNode root = mapper.readTree(cleaned);

            JsonNode issuesNode = root.path("issues");
            if (issuesNode.isArray() && !issuesNode.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode issue : issuesNode) {
                    sb.append("- ").append(issue.asText()).append("\n");
                }
                return sb.toString().trim();
            }

            JsonNode suggestionsNode = root.path("suggestions");
            if (suggestionsNode.isArray() && !suggestionsNode.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode suggestion : suggestionsNode) {
                    sb.append("- ").append(suggestion.asText()).append("\n");
                }
                return sb.toString().trim();
            }

            // 返回 summary 作为备选
            String summary = root.path("summary").asText();
            if (!summary.isEmpty()) {
                return summary;
            }
        } catch (Exception ignored) {
        }
        return "审查未通过，请改进执行结果";
    }

    /**
     * 获取记忆管理器
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    /**
     * 获取工具注册表（用于同步项目路径）
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    private synchronized void updateStep(List<ExecutionStep> steps, String stepId, ExecutionStep updated) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) {
                steps.set(i, updated);
                return;
            }
        }
    }

    /**
     * 并行执行一批相互独立的步骤。
     *
     * 每个步骤获取一个 Worker（池化，避免同一 Worker 被两个步骤并发占用），同时创建独立的 Reviewer 实例，
     * 流式输出写入步骤本地的 ByteArrayOutputStream；所有任务完成后按 step_id 顺序将缓冲区 flush 到 stdout。
     */
    private void runBatchParallel(List<ExecutionStep> batch, List<ExecutionStep> steps,
                                  Map<String, Integer> retryCount) {
        int parallelism = Math.min(batch.size(), workers.size());
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "yuforge-multi-agent");
            t.setDaemon(true);
            return t;
        });
        BlockingQueue<SubAgent> workerPool = new LinkedBlockingQueue<>(workers);
        Map<String, ByteArrayOutputStream> buffers = new ConcurrentHashMap<>();
        CompletionService<Void> completions = new ExecutorCompletionService<>(executor);
        long batchStartedNanos = System.nanoTime();
        if (renderer != null) {
            renderer.beginActivity("执行中 · 0/" + batch.size(), null);
        }

        for (ExecutionStep step : batch) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            buffers.put(step.id(), baos);
            PrintStream stepOut = new PrintStream(baos, true, StandardCharsets.UTF_8);
            String context = buildStepContext(steps, step);

            completions.submit(() -> {
                SubAgent worker = null;
                SubAgent localReviewer = new SubAgent(
                        "reviewer-" + step.id(), AgentRole.REVIEWER, llmClient, toolRegistry);
                try {
                    worker = workerPool.take();
                    runStep(step, steps, retryCount, worker, localReviewer, context, stepOut);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    updateStep(steps, step.id(), step.withFailed("并行执行被中断"));
                    stepOut.println(AnsiStyle.warning("  已中断 [" + step.id() + "]") + "\n");
                } catch (RuntimeException e) {
                    log.error("Parallel step {} failed unexpectedly", step.id(), e);
                    updateStep(steps, step.id(), step.withFailed("并行执行异常: " + e.getMessage()));
                    stepOut.println(AnsiStyle.error("  失败 [" + step.id() + "] · 并行执行异常 · "
                            + e.getMessage()) + "\n");
                } finally {
                    if (worker != null) {
                        worker.clearHistory();
                        workerPool.offer(worker);
                    }
                    stepOut.flush();
                }
                return null;
            });
        }

        int completed = 0;
        for (int i = 0; i < batch.size(); i++) {
            try {
                completions.take().get();
                completed++;
                if (renderer != null) {
                    renderer.updateActivity("执行中 · " + completed + "/" + batch.size(), null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Batch wait interrupted");
                break;
            } catch (ExecutionException e) {
                completed++;
                log.error("Parallel step task failed", e.getCause());
                if (renderer != null) {
                    renderer.updateActivity("执行中 · " + completed + "/" + batch.size(), null);
                }
            }
        }
        executor.shutdownNow();
        if (renderer != null) {
            renderer.endActivity();
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - batchStartedNanos);
        out.println(AnsiStyle.status("  执行批次完成 · " + completed + "/" + batch.size()
                + " · " + formatElapsed(elapsedMillis)));

        // 按 step_id 顺序 flush 各步骤的缓冲输出，保证用户看到的执行过程有稳定顺序
        for (ExecutionStep step : batch) {
            ByteArrayOutputStream buf = buffers.get(step.id());
            if (buf != null && buf.size() > 0) {
                out.print(buf.toString(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    private static String formatElapsed(long elapsedMillis) {
        if (elapsedMillis < 10_000L) {
            return String.format(Locale.ROOT, "%.1fs", elapsedMillis / 1000.0);
        }
        return (elapsedMillis / 1000L) + "s";
    }

    /**
     * 执行单个步骤（Worker 执行 + Reviewer 审查 + 最多 2 次重试）。
     *
     * 此方法被串行和并行两条路径共享，通过 {@code out} 控制流式输出目的地。
     */
    private void runStep(ExecutionStep step, List<ExecutionStep> steps,
                         Map<String, Integer> retryCount,
                         SubAgent worker, SubAgent reviewer, String context,
                         PrintStream out) {
        int ordinal = Math.max(1, steps.indexOf(step) + 1);
        String progress = ordinal + "/" + steps.size();
        out.println(AnsiStyle.status("  执行者 " + worker.getName() + " · " + progress + " · "
                + compactStatusText(step.description(), 100)));
        if (CancellationContext.isCancelled()) {
            updateStep(steps, step.id(), step.withFailed("用户取消"));
            out.println(AnsiStyle.warning("  已取消 [" + step.id() + "]") + "\n");
            return;
        }

        AgentMessage taskMsg = AgentMessage.task("orchestrator", step.description());
        AgentMessage result = worker.executeWithContext(taskMsg, context, out);
        if (CancellationContext.isCancelled()) {
            updateStep(steps, step.id(), step.withFailed("用户取消"));
            out.println(AnsiStyle.warning("  已取消 [" + step.id() + "]") + "\n");
            return;
        }

        if (result.type() == AgentMessage.Type.ERROR) {
            updateStep(steps, step.id(), step.withFailed(result.content()));
            out.println(AnsiStyle.error("  失败 [" + step.id() + "] · " + result.content()) + "\n");
            return;
        }
        if (result.content() == null || result.content().isBlank()) {
            updateStep(steps, step.id(), step.withFailed("执行结果为空"));
            out.println(AnsiStyle.error("  失败 [" + step.id() + "] · 结果为空") + "\n");
            return;
        }

        out.println(AnsiStyle.status("  审查者 " + reviewer.getName() + " · " + progress));
        AgentMessage reviewResult = reviewer.review(step.description(), result.content(), out);
        reviewer.clearHistory();

        if (reviewResult.type() == AgentMessage.Type.ERROR) {
            log.warn("Reviewer failed for step {}: {}", step.id(), reviewResult.content());
            out.println(AnsiStyle.warning("  审查不可用 [" + step.id() + "] · 保留当前执行结果") + "\n");
            updateStep(steps, step.id(), step.withResult(result.content()));
            return;
        }

        boolean approved = parseReviewApproval(reviewResult.content());
        String acceptedResult = result.content();

        if (approved) {
            updateStep(steps, step.id(), step.withResult(acceptedResult));
            out.println(AnsiStyle.success("  完成 · " + progress) + "\n");
            return;
        }

        int retries = retryCount.getOrDefault(step.id(), 0);
        String issues = parseReviewIssues(reviewResult.content());
        log.info("Step {} rejected (retry {}/{}): {}", step.id(), retries, MAX_RETRIES_PER_STEP, issues);

        while (!approved && retries < MAX_RETRIES_PER_STEP) {
            retries++;
            retryCount.put(step.id(), retries);
            out.println(AnsiStyle.warning("  重试 [" + step.id() + "] · 审查未通过"));
            out.println(AnsiStyle.subtle("    反馈：" + issues) + "\n");

            String feedbackContext = context + "\n\n之前的执行结果被审查拒绝，原因：\n" + issues;
            AgentMessage retryResult = worker.executeWithContext(taskMsg, feedbackContext, out);
            if (retryResult.type() == AgentMessage.Type.ERROR) {
                log.warn("Step {} retry {} failed at LLM layer: {}", step.id(), retries, retryResult.content());
                issues = "重试时 LLM 调用失败：" + retryResult.content();
                approved = false;
                continue;
            }
            if (retryResult.content() == null || retryResult.content().isBlank()) {
                acceptedResult = "执行结果为空";
                approved = false;
                issues = "执行结果为空";
                log.info("Step {} retry {} returned empty result", step.id(), retries);
                continue;
            }

            acceptedResult = retryResult.content();
            AgentMessage retryReview = reviewer.review(step.description(), acceptedResult, out);
            reviewer.clearHistory();

            if (retryReview.type() == AgentMessage.Type.ERROR) {
                log.warn("Reviewer failed for step {} retry {}: {}", step.id(), retries, retryReview.content());
                approved = true;
                issues = "";
                break;
            }

            approved = parseReviewApproval(retryReview.content());
            issues = parseReviewIssues(retryReview.content());
        }

        updateStep(steps, step.id(), step.withResult(acceptedResult));
        if (approved) {
            out.println(AnsiStyle.success("  完成 · " + progress + " · 重试后通过") + "\n");
        } else {
            out.println(AnsiStyle.warning("  达到重试上限 [" + step.id() + "] · 保留当前结果") + "\n");
        }
    }

    private String buildStepContext(List<ExecutionStep> steps, ExecutionStep currentStep) {
        StringBuilder context = new StringBuilder();
        context.append("总任务上下文：\n");

        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.COMPLETED && currentStep.dependencies().contains(step.id())) {
                context.append("已完成的依赖步骤 [").append(step.id()).append("]: ")
                        .append(step.description()).append("\n");
                if (step.result() != null && !step.result().isBlank()) {
                    String preview = step.result().length() > 500
                            ? step.result().substring(0, 500) + "..."
                            : step.result();
                    context.append("结果：").append(preview).append("\n");
                }
                context.append("\n");
            }
        }

        return context.toString();
    }

    private String summarizeSteps(List<ExecutionStep> steps) {
        StringBuilder sb = new StringBuilder();
        int visible = Math.min(steps.size(), 8);
        for (int i = 0; i < visible; i++) {
            ExecutionStep step = steps.get(i);
            sb.append(String.format("    %d. %s%n", i + 1, compactStatusText(step.description(), 100)));
        }
        if (steps.size() > visible) {
            sb.append("    ... ").append(steps.size() - visible).append(" more steps\n");
        }
        return sb.toString();
    }

    private static String compactStatusText(String text, int maxChars) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(1, maxChars - 3)) + "...";
    }

    /**
     * 构建最终汇总。
     *
     * Worker/Reviewer 的中间正文不进入默认 transcript；这里返回步骤状态和结果预览，
     * 作为用户可见的唯一结果摘要。
     */
    private String buildFinalResult(List<ExecutionStep> steps) {
        StringBuilder result = new StringBuilder();
        boolean allCompleted = steps.stream().allMatch(step -> step.status() == StepStatus.COMPLETED);
        boolean hasFailedSteps = steps.stream().anyMatch(step -> step.status() == StepStatus.FAILED);

        if (allCompleted) {
            result.append("Multi-Agent 任务已完成。\n\n");
        } else if (hasFailedSteps) {
            result.append("Multi-Agent 任务结束，但存在失败步骤。\n\n");
        } else {
            result.append("Multi-Agent 任务部分完成。\n\n");
        }
        result.append("执行摘要：\n");

        for (ExecutionStep step : steps) {
            result.append("[").append(step.id()).append("] ");
            if (step.status() == StepStatus.COMPLETED) {
                result.append("完成 · ");
            } else if (step.status() == StepStatus.FAILED) {
                result.append("失败 · ");
            } else {
                result.append("等待 · ");
            }
            result.append(step.description()).append("\n");

            if (step.result() != null && !step.result().isBlank()) {
                String preview = step.result().length() > 120
                        ? step.result().substring(0, 120) + "..."
                        : step.result();
                result.append("   结果：").append(preview).append("\n");
            }
        }

        return result.toString();
    }
}
