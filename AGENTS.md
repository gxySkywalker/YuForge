# AGENTS.md

仓库给 Agent / 新线程使用的首读入口。详细行为描述见 `docs/agents-reference.md`。

## 信息优先级

1. 代码实际行为 > 2. `AGENTS.md` > 3. `YUFORGE.md` > 4. `README.md` > 5. `ROADMAP.md` > 6. `CLAUDE.md`

`ROADMAP.md` 代表演进方向，不代表已交付。

## 项目快照

- 项目名：`YuForge`
- 定位：面向商业使用的 Java Agent CLI 产品，对标 Claude Code
- 已交付 23 期（ReAct → Plan+DAG → Memory → RAG → Multi-Agent → HITL → 并行工具 → 多模型 → 联网 → MCP 核心 → MCP 高级 → 长上下文 → Chrome DevTools → CDP 会话复用 → Skill → TUI → LSP 诊断 → Side-Git 快照 → Prompt 分层 → Runtime API → 图片输入 → 微信 iLink 通道文本 MVP）
- `YUFORGE.md` 是 YuForge 的项目级记忆文件：启动时自动注入 system prompt，适合团队共享的长期稳定规则；个人/会变化的经验继续用 `/save` 长期记忆。
- 下一步：OAuth / sampling / recovery 作为后续 MCP 增强
- Banner 版本：`v16.1.0`，Maven 产物：`yuforge-1.0-SNAPSHOT.jar`（两者不一致是正常状态）

## 运行前提

- Java 17+ / Maven
- 可选：`ripgrep`（`grep_code` 会优先使用；未安装时自动回退 Java 扫描）
- 至少一个 API Key：`GLM_API_KEY` / `DEEPSEEK_API_KEY` / `STEP_API_KEY` / `KIMI_API_KEY` / `FREELLMAPI_API_KEY` / `XFYUN_MAAS_API_KEY` / `AGNES_API_KEY`

## 常用命令

```bash
cp .env.example .env
mvn clean package        # 默认跳过测试，优先产出可手工验收 jar
java -jar target/yuforge-1.0-SNAPSHOT.jar
java -jar target/yuforge-1.0-SNAPSHOT.jar wechat setup   # 主动绑定微信 iLink 通道，默认不开启
java -jar target/yuforge-1.0-SNAPSHOT.jar wechat start   # 前台启动微信通道
/wechat                   # 交互式 CLI 内扫码绑定并后台启动微信通道
mvn test -Pquick          # 常规回归
mvn test -Pphase16-smoke  # TUI 相关
mvn test -Dtest=XxxTest -DskipTests=false   # 针对性
mvn test -DskipTests=false                  # 全量回归
/init                    # 生成精简项目级记忆 YUFORGE.md；已有文件不覆盖，/init --force 可重写
/export                  # 导出当前 ReAct 会话为 Markdown，包含完整 system prompt
```

## 架构概览

三条主执行路径，共享 ToolRegistry / MemoryManager / SnapshotService：

| 路径 | 入口 | 触发 |
|------|------|------|
| ReAct | `Agent.java` | 默认模式 |
| Plan-and-Execute | `PlanExecuteAgent.java` | `/plan` |
| Multi-Agent | `AgentOrchestrator.java` | `/team` |

核心内置工具包括：`read_file` / `write_file` / `list_dir` / `glob_files` / `grep_code` / `execute_command` / `create_project` / `search_code` / `web_search` / `web_fetch` / `save_memory` / `revert_turn` / `read_tool_artifact`。其中 `read_tool_artifact` 只用于按结构化历史检查点里的 `artifact_id` 恢复被上下文治理归档的旧工具结果。

代码库理解默认走 Claude Code 式实时探索：`glob_files` 找候选文件、`grep_code` 精确定位符号或字符串、`read_file` 按需读取具体行段。`grep_code` 优先使用本机 `ripgrep`，不可用时回退到 Java 扫描；结果受 `max_results` / `head_limit` / `max_chars` 预算约束，返回 `partial: true` 或 `suggested_reads` 时应继续缩小搜索范围或按建议读取行段。`search_code` 是 RAG 语义辅助，适合模糊自然语言、关键词不明确、常规搜索无果、巨型/跨知识检索场景，不作为精确代码定位的首选。`glob_files` / `grep_code` 对模型统一返回 `/` 分隔的项目相对路径；`execute_command` 在 Windows 使用 PowerShell、其他平台使用 bash，并把实际 shell 放入当前 user turn。

MCP 动态工具：`mcp__{server}__{tool}`（+ resources 虚拟工具）

MCP 配置会合并用户级 `~/.yuforge/mcp.json` 与项目级 `.yuforge/mcp.json`；`${VAR}` 支持系统环境变量、系统属性、项目 `.env`、用户 `~/.env`。检测到 `STEP_API_KEY` 时会自动内置 `step_search` 远程 MCP（显式同名配置优先）。

DeepSeek V4 / Kimi thinking 模式下，assistant tool-call 消息的 `reasoning_content` 必须随下一轮请求历史带回；其他 provider 默认只把 reasoning 写日志 / 展示。
DeepSeek SSE 调用默认强制 HTTP/1.1，避免部分网络/网关下 HTTP/2 长流被远端重置成 `stream was reset: INTERNAL_ERROR`。
DeepSeek 当前按文本 provider 处理：`supportsImageInput()` 返回 false，历史或工具回灌里的图片 `ContentPart` 会在请求序列化时替换为文本提示，不能把 `image_url` block 发给 DeepSeek API。

讯飞星辰 MaaS provider 名为 `xfyun`，默认 Base URL 为 `https://maas-api.cn-huabei-1.xf-yun.com/v2`。`model` 必须使用服务管控页展示的 `modelId`；公开模型名 / Hugging Face 仓库名不一定可直接调用。微调模型用 `/config provider xfyun --lora-id <resourceId>` 配置服务卡片上的 resourceId，YuForge 会作为 HTTP header `lora_id` 发出。`xfyun` 当前按 MaaS 文档走纯对话请求，不向上游发送 YuForge 内置工具列表。
Agnes provider 名为 `agnes`，默认 Base URL 为 `https://apihub.agnes-ai.com/v1`，默认模型 `agnes-2.0-flash`，走 OpenAI-compatible Chat Completions，默认 1M context window，支持流式输出和 tools。

## 仓库结构

```
src/main/java/com/yuforge/
├── agent/       Agent.java, PlanExecuteAgent.java, SubAgent.java, AgentOrchestrator.java
├── cli/         Main.java, CliCommandParser.java, PlanReviewInputParser.java
├── browser/     BrowserSession, BrowserGuard, SensitivePagePolicy
├── llm/         GLMClient, DeepSeekClient, StepClient, KimiClient, FreeLlmApiClient, AgnesClient
├── context/     ContextProfile, ContextMode, TokenUsageFormatter
├── memory/      MemoryManager, ConversationHistoryCompactor, ToolResultArtifactStore, LongTermMemory
├── plan/        Planner, ExecutionPlan, Task
├── rag/         CodeIndex, CodeRetriever, VectorStore, CodeChunker
├── lsp/         LspManager, LspDiagnosticFormatter
├── prompt/      PromptAssembler, PromptContext, PromptRepository
├── image/       ImageReferenceParser
├── runtime/     api/ (RuntimeApiServer) + task/ (DurableTaskManager)
├── snapshot/    SideGitManager, SnapshotService
├── tool/        ToolRegistry
├── wechat/      iLink client, account store, message loop, non-interactive policy
├── mcp/         McpClient, McpServerManager, transport/, resources/, mention/
├── hitl/        HitlToolRegistry, ApprovalPolicy, TerminalHitlHandler
├── web/         SearchProvider, WebFetcher, HtmlExtractor, NetworkPolicy
├── policy/      PathGuard, CommandGuard, AuditLog
├── skill/       SkillRegistry, SkillContextBuffer, SkillIndexFormatter
└── render/      Renderer, InlineRenderer, PlainRenderer, RendererFactory
```

启动与 inline 渲染当前约定：

- 开屏 Banner 使用无右边框的简洁布局，避免 CJK/ANSI 字宽导致右侧竖线错位；Phase 22 后默认是 YU 主题彩色 logo + Qoder 风格首屏，只展示模型、MCP、Skill、ReAct 状态和三条 getting-started tips，不再把 MCP server 明细刷成启动日志。
- inline 模式使用 JLine 4 的 LineReader 编辑能力，默认提示符是 `* `，右提示显示 `message / @path / @image`。
- inline 输入期右提示应包含 `Esc clear`，表示仅清空当前编辑缓冲；任务运行期活动面板显示 `Esc cancel`，表示请求取消当前 run。二者不能混淆，取消后必须在 transcript 给出明确终态。
- 默认 CLI 启动路径应先 `Renderer.start()` 并初始化底部 dock；inline 首屏不要在 `readLine` 前裸写 stdout，而是通过 `InlineRenderer.installStartupScreen(...)` 挂到 `LineReader.CALLBACK_INIT`，首次进入输入时用 `printAbove` 一次性显示完整 Banner + tips，避免 logo 被 LineReader 首次重绘滚出可视区域。
- `BottomStatusBar` 现在是 JLine `Status` 托管的底部 dock：由 JLine 维护滚动区域和状态行位置，不再手写 `\n` / `moveUp` / `CLEAR_TO_EOS` 清屏。输入期会把 LineReader 光标定位到 dock 上方一行，让 `*` 输入行和 Status 同处底部区域；dock 保留两类信息：上层模式 + MCP/Skill 摘要，下层 Auto Model / model / phase / ctx 百分比与 token / cost / elapsed / cwd。关键字段可用克制的 JLine `AttributedString` 彩色样式突出，但纯文本格式和宽度裁剪逻辑要保持稳定。`ctx` 表示当前仍会带入下一轮请求的上下文估算；`in/out/cache` 表示最近任务的 LLM 调用统计，二者不要混用。
- dock 必须按终端列宽做信息降级：宽屏可显示完整 usage/cost/cwd，中屏优先 model、phase、ctx、elapsed，窄屏只保留模式、model、phase 和 ctx 百分比；所有宽度计算使用 JLine columnLength/columnSubSequence，不能按 Java `String.length()` 截断 CJK 或 emoji。
- 普通任务和斜杠命令提交后，`Main` 会把本轮原始输入以暗色整行块写回 transcript：输入态左提示仍是 `* `，提交回显左提示改为 `>`；单行输入只占一行，不额外追加空白行。普通任务随后再展开 MCP resource / 本地 `@path` 并进入 Agent；不要只依赖 JLine 提交行残留，否则 activity 重绘或 dock 刷新可能让用户输入从可见历史里消失。`/clear` 清空 conversationHistory、shortTermMemory、待注入 Skill buffer 和会话 TODO，并重建不含上一轮检索记忆的 system prompt；长期记忆保留。`/compact` 会手动压缩当前 ReAct conversationHistory，不等待上下文阈值触发，保留最近 1 个 user 轮次和 tool_call/tool_result 边界。
- 复杂任务可由 Agent 用 `rewrite_todo_list` / `update_todo_status` 维护会话内 TODO；它是外部工作记忆，进入后续 user turn，并在底部 dock phase 中显示摘要。TODO 不落盘、不写入长期记忆，也不替代 Plan DAG 或后台 `/task`。
- ReAct LLM 调用期间，inline renderer 默认只显示短暂的 `Thinking...` 活动态和工具进度，不把 provider 原始 `reasoning_content` 落入 transcript；模型历史与日志仍按协议保留。仅本地排障可通过 `-Dyuforge.render.show_reasoning=true` 或 `YUFORGE_RENDER_SHOW_REASONING=true` 显式回显；Plan task / SubAgent 同样遵守此开关。活动区只能清理自己刚打印的几行，不能用独立 JLine `Display.update()` / `CLEAR_TO_EOS` 向上覆盖 transcript。
- inline 流式代码块先显示稳定的 `generating code` 行，结束时追加可折叠代码块；不得依赖 ANSI `moveUp` / `CLEAR_TO_EOS` 回退覆盖已写出的 transcript，避免宽字符换行或异步输出导致 scrollback 错位。
- 交互期输出应优先走 `Renderer.stream()`；`Main`、`PlanExecuteAgent`、`Planner`、`AgentOrchestrator` 都支持把输出流接到 inline renderer，避免直接争抢 stdout。`CodeIndex` 的索引进度通过 `ProgressListener` 注入，`/index` 应绑定到当前 renderer 输出流。
- Phase 22 开始，`InlineRenderer` 可绑定当前 `LineReader`；当 `LineReader.isReading()` 为 true 时，`Renderer.stream()` 的完整行输出优先通过 `LineReader#printAbove` 显示在输入行上方，未绑定 / 非读取态 / 测试路径回退到原 `PrintStream`。
- Markdown 表格渲染要按当前终端列宽分配列宽；长内容在单元格内部换行，不能依赖终端自动折行把整行表格打散。
- ReAct 正常结束后不再把 `📊 Token: ...` 打进正文区；token/cost/elapsed 会保留在底部强状态行，phase 回到 `idle`。
- 默认 CLI 启动路径应尽早建立 `Terminal -> LineReader -> Renderer`，启动 Banner、模型加载、MCP 启动、Skill summary、ReAct 提示和退出提示都应走 `Renderer.stream()`；除 fatal bootstrap / runtime API / legacy TUI 降级外，不要在交互主路径新增裸 `System.out.println`。
- 启动期 MCP 不得阻塞首屏：CLI 默认最多等待 8 秒（`YUFORGE_MCP_STARTUP_WAIT_SECONDS` / `-Dyuforge.mcp.startup.wait.seconds` 可调），超时后保留未完成 server 为 `STARTING` 并后台继续初始化；`/mcp` 查看最新状态。
- `LineReader` 使用 `YuForgeHighlighter` 做输入实时高亮：slash 命令、`@` 引用、`@image:`、`@clipboard`、敏感词和明显危险 shell 片段会在编辑阶段被标记；不要把这类视觉提示混入最终提交文本。
- `LineReader` 使用 `YuForgeCompleter` 做上下文补全：`/model` provider、`/mcp` 子命令与 server、`/skill` 子命令与 skill name、`/task` / `/browser` / `/snapshot` 子命令、`@image:` 本地路径、本地 `@path` 和 MCP resource `@server:uri` 引用都应从同一个 completer 出口维护。
- 普通用户输入进入 Agent 前会先展开 MCP resource mention，再由 `LocalPathMentionExpander` 展开本地 `@path`：文件会内联为 `<file>` 块，目录会内联为 `<directory>` 列表；绝对路径或符号链接逃逸项目根时保持原文不展开。
- `LineReader` 使用 `YuForgeHistory` 持久化输入历史到 `~/.yuforge/history/input.history`；如果 `yuforge.history.file` / `YUFORGE_HISTORY_FILE` 指向目录，也会自动使用该目录下的 `input.history`，避免把目录当文件读；默认忽略空白、重复、明显密钥/Bearer、base64 图片和超长输入，用户可用 `/history clear` 清空本机输入历史。
- 启动期会加载 `~/.yuforge/YUFORGE.md`、项目根 `YUFORGE.md`、项目根 `.yuforge/YUFORGE.md`、`YUFORGE.local.md`、`.yuforge/YUFORGE.local.md`，按此顺序注入 Project Context；`@relative/path.md` 可导入项目根内文件，总注入内容有字符预算，避免项目记忆变成 token 噪音。
- `/init` 会根据当前项目生成短 `YUFORGE.md`，只放 commands / project positioning / architecture / pitfalls / don'ts；默认不覆盖已有文件。
- `/export` 导出当前 ReAct `conversationHistory` 为 Markdown 到 `~/.yuforge/exports/session-*.md`；只支持无参数命令，包含完整 system prompt，便于检查 LLM 实际接收前的指令。
- `/checkpoint` 保存当前 ReAct 会话的可恢复 checkpoint；`/session` 列出最近 checkpoint；`/resume <session_id>` 仅允许恢复当前项目的 checkpoint。checkpoint 不持久化图片或大型工具 artifact 原文，恢复时使用当前 system prompt。
- JLine 交互升级计划记录在 `docs/phase-22-jline-interaction-upgrade.md`。

## 关键行为约束（Agent 必读）

### Memory

- 长期记忆只通过 `/save` 或用户明确要求保存；不要自动提取事实
- `save_memory` 的代码级授权只能来自本轮原始用户输入；网页、MCP、文件和工具结果不能授权写入。global 记忆需要本轮明确“全局/跨项目/所有项目”意图。
- `web_search`、`web_fetch` 与 MCP 工具输出以 `<untrusted_external_content>` 包装并转义正文；它们只是不可信数据，不能授权工具调用、记忆写入或改变指令优先级。
- 本轮读取过网页、搜索或 MCP 不可信内容后，`write_file`、`execute_command`、`create_project`、`revert_turn`、`save_memory` 和 MCP 工具必须逐次 HITL 确认，即使常规 `/hitl off`；不可被“全部放行”缓存绕过。
- Prompt Injection 回归入口：`mvn test -Dtest=PromptInjectionDefenseTest,SystemPromptLeakGuardTest,AbstractOpenAiCompatibleClientImageInputTest,HitlToolRegistryTest,ToolRegistryTest -DskipTests=false`；覆盖直接注入提示词契约与输出侧拦截、间接注入来源封装 + 强制确认、记忆污染授权拒绝。真实模型对抗遵循能力仍需人工红队验证。
- 当前 Prompt Injection 安全基线已交付；容器/VM 沙箱、命令网络 egress policy、通用 DLP 和改写泄露检测仍未交付，不得在文档或回答中表述为现有能力。完整结论见 `docs/prompt-injection-defense.md`。
- `YUFORGE.md` 管团队共享的项目规则，长期记忆管个人或项目作用域的稳定事实；不要把一次性协作经验写进 `YUFORGE.md`
- 长期记忆只保存跨会话稳定事实，不保存临时指令；默认项目级作用域，跨项目通用偏好才用 global
- 长期记忆检索采用关键词 + 元数据轻量加权，并按相关度、时间、id 做确定性排序；空查询不注入记忆，避免无关事实污染上下文。`MemoryRetrievalGoldenSetTest` 固化相关性、项目隔离、空查询三类回归样例，后续替换为 hybrid retrieval 时必须保持通过。
- `MemoryRetrievalTrace` 只在本地 `/memory status`、`/context` 与日志记录本轮召回数量、入选 id/score/token、预算截断原因；不携带原始查询或记忆正文，也不注入模型消息。
- `MemoryRetrievalStrategy` 是记忆召回的替换边界：默认 `KeywordMemoryRetrievalStrategy` 零依赖离线可用；未来 Hybrid/Embedding 策略必须保留关键词回退，并复用同一注入协议、Trace 和 Golden Set。
- 长期记忆必须可审计和可删除：`/memory list` / `/memory search <关键词>` / `/memory delete <id>` / `/memory clear`。默认 list/search/delete/clear 仅限当前项目可见条目（project + global）；`/memory clear --global` 只清 global，`/memory clear --all` 才清全部。
- 两道压缩不要混淆：shortTermMemory 压缩 vs conversationHistory 上下文治理（后者真正控制下一轮 LLM input）。
- conversationHistory 达到高水位后先把旧的大型 tool_result 归档到有界 `ToolResultArtifactStore`，原 tool message 替换为带 `artifact_id` / preview 的协议安全占位符；模型确需原文时调用 `read_tool_artifact`。进入摘要区的大结果必须先归档。
- 归档后仍超阈值时，按 user 边界保留最近 3 轮完整事务，把旧历史用全量分块 + Reduce 压成六段结构化工程检查点；禁止固定字符截断造成中间历史空洞。`/compact` 保留最近 1 个 user 轮次。
- 自动压缩阈值为 `min(window - summaryReserve - buffer, window * 80%)`；summaryReserve 最大 20k、buffer 最大 13k，小窗口按比例缩小。请求预算还要扣除工具 schema 估算。
- 查询相关的动态长期记忆放在本轮 user message 尾部的 `<relevant-memory>` 块，不再重写 system message；工具定义按名称稳定排序，以提高 exact-prefix prompt cache 命中机会。
- `PromptAssembler` 必须保持 system prompt 连续稳定：base → personality → mode → approval → context management → handoff → project context → skills。日期/时区、workspace、shell、相关记忆与 MCP resource index 由 `RuntimeContextFormatter` 放入当前 user turn，避免动态内容截断 system prompt cache。
- 工具结果由 `ToolResultDiagnostic` 归一化为错误码、retryable 与恢复建议；ReAct / Plan / SubAgent 使用 `ToolAttemptTracker` 按“工具名 + 规范化参数”计数，同一错误第二次必须换参数或换工具，第三次停止原样重试。完整调用栈只写日志，不回灌模型。
- inline CLI 的 ESC、TUI `/cancel` 与微信取消通过 `CancellationToken` 传递到 Agent、工具和 OpenAI-compatible LLM 的 OkHttp `Call.cancel()`；同步 SSE 请求会尽力立即断开，而不是只等待模型返回后停止后续循环。
- 当前取消能力不包含“插话后工具后台续跑”的事件队列状态机；若后续实现，原 tool call 只能写一个终态 tool result，迟到结果必须以独立 `late_tool_completion` 事件回流。设计与边界见 `docs/cancellation-and-interruption.md`。

### HITL + 策略层

- 拦截顺序：HitlToolRegistry → ToolRegistry → PathGuard/CommandGuard
- 用户无法批准策略拒绝的请求
- PathGuard 强制路径限定在项目根内
- CommandGuard 是辅助黑名单，不是主防线
- 微信 iLink 通道没有人工审批面板，必须走非交互式默认拒绝策略：只读工具（含 `read_tool_artifact`）默认允许，`execute_command` 必须精确命中命令白名单，`mcp__*` 必须命中 MCP 白名单，`revert_turn` 和浏览器会话切换默认拒绝，文件写入仍由 PathGuard 限定在绑定 workspace 内。

### Plan 审阅交互

- `Enter` 执行 / `Ctrl+O` 展开 / `ESC` 取消 / `I` 补充重规划
- 方向键不应被误判为 ESC
- 涉及改动要连 raw mode 和回退路径一起看

### 并行工具

- 三条路径都走 `executeTools()`，不手写 for-loop
- 默认最多 4 个并发，结果保持原始顺序

### Web + Browser

- 每轮当前 user turn 的 `<environment_context>` 会注入时间戳、日期/时区、workspace、OS 与实际命令 shell，用于相对日期和环境理解；这些动态字段不得放回 system prompt。联网搜索不再由 prompt 的 Freshness Policy 强制，是否调用 `web_search` 交给模型基于工具 schema 和用户目标自主决定。
- “当前项目/当前 README/当前文件/当前代码”等表达属于本地上下文任务，通常应由模型选择 `glob_files` / `grep_code` / `read_file`，而不是联网工具。
- 当前模型为 `step-3.7-flash*` 且自动/显式 `step_search` MCP 的 `web_search` / `web_fetch` 已就绪时，内置 `web_search` / `web_fetch` 会优先转调 StepSearch MCP；未就绪或调用失败时回退到原 SearchProvider / WebFetcher。
- 已知 URL 先 `web_fetch`，SPA/防爬墙 fallback 到 Chrome DevTools MCP
- 浏览器读取优先 `take_snapshot`，不默认 `take_screenshot`
- 公开页面不要提前切 shared 模式

### Skill

- system prompt 索引段注入三处提示词，上限 20 个 / 4KB
- `load_skill` → SkillContextBuffer → 下一轮 user message 前置注入

## 修改时的硬规则

### 1. 改行为 → 同步文档

`AGENTS.md` / `README.md` / `ROADMAP.md`（仅状态变化时）

### 2. 改命令入口 → 联动

`Main.java` + `CliCommandParser.java` + 测试 + `README.md` + `AGENTS.md`

未识别的 `/xxx` 在 CLI 层直接报"未知命令"，不回退给 Agent。

### 3. 改 Plan 审阅交互 → 联动

`Main.java` + `PlanReviewInputParser.java` + 测试 + 手工验证

### 4. 改工具集 → 联动

`ToolRegistry.java` + Agent/PlanExecuteAgent/SubAgent 提示词 + 可能 Planner 提示词 + 文档

### 5. 改模型/接口 → 联动

对应 Client + `LlmClientFactory.java` + `.env.example` + 文档

### 5.1 改 Embedding → `EmbeddingClient` + `VectorStore` + `.env.example` + 文档

### 5.2 改 Web/搜索 → `web/` 相关 + ToolRegistry + `.env.example` + 文档 + 测试

### 5.3 改 Memory → `MemoryManager` + `LongTermMemory` + `TokenBudget` + 测试 + 文档

### 5.4 改 HITL/策略 → `policy/` + ToolRegistry + HitlToolRegistry + 提示词 + `.env.example` + 文档 + 测试

### 5.5 改 MCP → `mcp/` + ToolRegistry + HITL + AuditLog + 提示词 + 文档 + 测试

### 6. 不提交 `.env` / 真实 API Key / `target/` 产物

### 7. 保持代码可读性，不过度抽象

## 验证路径

| 场景 | 命令 |
|------|------|
| 代码搜索工具 | `mvn test -Dtest=ToolRegistryTest,CodeSearchGoldenSetTest,ApprovalPolicyTest` |
| 命令解析 | `mvn test -Dtest=CliCommandParserTest,PlanReviewInputParserTest,MainInputNormalizationTest` |
| DAG/Plan | `mvn test -Dtest=ExecutionPlanTest` |
| Multi-Agent | `mvn test -Dtest=AgentRoleTest,AgentMessageTest,AgentOrchestratorTest` |
| TUI/终端 | `mvn test -Pphase16-smoke` |
| RAG | `mvn test -Dtest=CodeChunkerTest,CodeAnalyzerTest,VectorStoreTest,CodeIndexTest` |
| 常规回归 | `mvn test -Pquick` |

## 给新线程的导航

1. 先看本文件 → 2. `README.md` → 3. `Main.java` → 4. 按任务进入对应模块

| 任务类型 | 先看 |
|----------|------|
| CLI 命令 | Main.java + CliCommandParser.java |
| 规划/DAG | PlanExecuteAgent.java + Planner.java + ExecutionPlan.java |
| 工具调用 | ToolRegistry.java + Agent.java |
| 代码搜索 | ToolRegistry.java (`glob_files` / `grep_code` / `read_file`) |
| 模型/API | llm/*Client.java + LlmClientFactory.java |
| RAG 语义辅助 | CodeRetriever.java + CodeIndex.java + VectorStore.java |
| Multi-Agent | AgentOrchestrator.java + SubAgent.java |
| MCP | McpServerManager.java + McpClient.java |
| TUI/渲染 | render/Renderer.java + RendererFactory.java |

## 当前已知边界

以下在路线图但未交付：容器/VM 沙箱 / MCP OAuth + sampling + server 自动重启

不要把 `ROADMAP.md` 中"将来要做"误读成"现在已有"。

## 持续维护约定

形成稳定协作规则时直接补进本文件，不要只留在聊天记录里。详细实现细节补到 `docs/agents-reference.md`。
