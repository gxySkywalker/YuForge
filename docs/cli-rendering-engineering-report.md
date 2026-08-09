# YuForge CLI 渲染与交互工程报告

## 目标与结论

本阶段将 YuForge 的终端从“能输出”收敛为适合长时 Agent 任务的可读、可恢复、可验证交互界面。结论是：默认 ReAct、Plan-and-Execute 与 Multi-Agent 单步路径已共享同一套渲染契约；并行 Team 保持确定性输出，不以炫技牺牲终端正确性。

## 已交付能力

1. **稳定 transcript**：提交后的用户输入以 `>` 回显；流式代码先显示 `generating code`，完成后追加折叠块，不再用 ANSI 光标回退覆盖既有历史。
2. **稳定优先的状态呈现**：曾实现基于 JLine `Status` 的响应式底部 dock；但在 Windows Terminal 的缩放、全屏和切标签场景中，动态 dock 与右提示会留下残影或重复行。因此默认 inline 路径改为无 dock、无右提示的追加式 transcript。需要本地实验时可用 `-Dyuforge.inline.bottom-dock=true` 开启 dock，但它不是默认产品路径。
3. **清晰的运行反馈**：工具结果只输出安全的一行摘要；ESC 在输入期表示清空缓冲，在运行期表示请求取消，避免歧义。
4. **reasoning 默认不落盘到 transcript**：默认仅显示短暂 Thinking 活动态、工具进度和最终回答。模型协议历史与本地日志仍保留必要 reasoning；排障可用 `-Dyuforge.render.show_reasoning=true` 显式开启回显。
5. **三条 Agent 路径一致化**：ReAct、`/plan` 与 `/team` 的单步直连工具调用使用 `Renderer.appendToolCalls`，获得相同的折叠工具卡片；Plan/Team 单步 LLM 请求也会驱动 Thinking 活动态。

## 核心工程取舍

### 为什么默认不使用动态 dock、右提示或覆盖历史行

终端宽度变化、CJK 宽字符、异步 MCP 输出都会使“move up 一行再替换”的假设失效。Windows Terminal 会在缩放、全屏和切标签时重排滚屏，而动态右提示或 dock 不能可靠地在旧帧上擦除。YuForge 默认采用无右提示、无动态 dock 的追加式 transcript：生成中提示和最终折叠块同时保留。它略多一行，但 scrollback 可审计，且不会破坏用户已看到的历史。

### 为什么默认不显示原始 reasoning

原始 reasoning 会稀释最终结论、造成长输出噪音，也不应被当作稳定执行日志。默认活动面板只回答“系统正在工作”；详细 reasoning 仍在模型历史和日志中，并以显式 debug 开关提供给开发者。

### 为什么并行 Team 不直接调用 Renderer

Renderer 面向单一终端流。多个 Worker 同时写入会造成工具块、Markdown 和 activity 区交错。并行批次因此先写独立 `PrintStream` 缓冲，批次结束后按 `step_id` 顺序 flush；单步任务才直连 Renderer。这是在实时性和可读性之间的有意识边界。

## 架构关系

```text
Main
  └─ Renderer (InlineRenderer / PlainRenderer)
      ├─ ReAct Agent: 工具块 + Thinking
      ├─ PlanExecuteAgent: 单 task 工具块 + Thinking
      └─ AgentOrchestrator
          ├─ 单步 SubAgent: 工具块 + Thinking
          └─ 并行 Worker: 独立缓冲 → 按 step_id 顺序输出
```

## 验证证据

- `mvn test -Dtest=ReasoningDisplayPolicyTest,InlineRendererTest,AgentStreamRendererTest -DskipTests=false`：22 项通过。
- `mvn test -Pphase16-smoke`：108 项通过。
- Plan、Multi-Agent、SubAgent、Inline Renderer、Tool Renderer 组合回归：50 项通过；后续 Thinking 接线回归：39 项通过。
- `mvn package` 成功生成 jar；真实 GLM 单轮 CLI 验收中，输入“只回复 OK”只显示最终 `OK`，未回显 reasoning。

## 已知边界

- Thinking 活动面板是状态提示，不是完整推理可视化；详细回显仅用于本地排障。
- Team 并行批次刻意不提供共享 live thinking，避免多线程终端竞争。
- 本阶段不引入容器隔离、终端录制回放或远程 UI；这些属于不同的产品/安全议题。

## 面试表达

“我没有把 CLI 当作 println 的外壳，而是把它视为 Agent 的运行控制面。核心约束是 transcript 不能被异步输出破坏、上下文状态要在窄终端可读、并行任务不能抢同一个终端。实现上我用 Renderer 抽象收口三条 Agent 路径；单步任务复用折叠工具块和 Thinking，真正并行的 Worker 则先缓冲再确定性 flush。默认不展示原始 reasoning，只保留活动状态和最终答案，既减少噪音，也保留日志与 debug 开关用于排障。最后我用单测、JLine smoke 和真实模型单轮调用三层验证，而不是只看截图。”
