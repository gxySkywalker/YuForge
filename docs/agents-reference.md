# AGENTS Reference: Detailed Feature Behavior

This document contains detailed feature behavior descriptions, configuration reading orders, and implementation notes that were previously in `AGENTS.md`. Consult this when working on specific modules.

For the primary entry point, see `/AGENTS.md`.

---

## Configuration Reading Orders

### API Key

1. `~/.yuforge/config.json` 中对应 provider 的 `apiKey`
2. 环境变量：`GLM_API_KEY` / `DEEPSEEK_API_KEY` / `STEP_API_KEY` / `KIMI_API_KEY` / `FREELLMAPI_API_KEY` / `XFYUN_MAAS_API_KEY` / `AGNES_API_KEY`（Kimi 兼容 `MOONSHOT_API_KEY`，讯飞 MaaS 兼容 `XFYUN_API_KEY`）
3. 仓库当前目录下的 `.env`
4. 用户主目录下的 `.env`

### Persistence Locations

| 数据 | 默认路径 | 覆盖方式 |
|------|----------|----------|
| 长期记忆 | `~/.yuforge/memory/long_term_memory.json` | `-Dyuforge.memory.dir` |
| 项目级记忆 | `YUFORGE.md` / `.yuforge/YUFORGE.md` / `YUFORGE.local.md` | 用户级稳定偏好：`~/.yuforge/YUFORGE.md` |
| RAG 索引 | `~/.yuforge/rag/codebase.db` | `-Dyuforge.rag.dir` |
| 审计日志 | `~/.yuforge/audit/audit-YYYY-MM-DD.jsonl` | `YUFORGE_AUDIT_DIR` / `-Dyuforge.audit.dir` |
| Side-Git 快照 | `~/.yuforge/snapshots/<project_hash>/<worktree_hash>/.git` | `YUFORGE_SNAPSHOT_DIR` / `-Dyuforge.snapshot.dir` |
| 后台任务 | `~/.yuforge/tasks/tasks.db` | — |

### Snapshot Config

系统属性 > 环境变量 > 默认值：`yuforge.snapshot.enabled`(true) / `yuforge.snapshot.max`(50) / `yuforge.snapshot.excludes`(.git,.yuforge/snapshots,target,node_modules,dist,.idea,*.class,*.jar) / `yuforge.snapshot.dir`(~/.yuforge/snapshots)

### Embedding Config

环境变量 > 系统属性 > 默认值：`EMBEDDING_PROVIDER`(ollama) / `EMBEDDING_MODEL`(nomic-embed-text:latest) / `EMBEDDING_BASE_URL`(http://localhost:11434)

### Log Config

系统属性 > 环境变量/.env > 默认值：`YUFORGE_LOG_DIR`(~/.yuforge/logs) / `YUFORGE_LOG_LEVEL`(INFO) / `YUFORGE_LOG_MAX_HISTORY`(7) / `YUFORGE_LOG_MAX_FILE_SIZE`(10MB) / `YUFORGE_LOG_TOTAL_SIZE_CAP`(100MB)

### ReAct/SubAgent Budget Config

系统属性 > 默认值：`yuforge.react.token.budget`(Integer.MAX_VALUE) / `yuforge.react.stagnation.window`(3) / `yuforge.react.hard.max.iterations`(50)

设计取舍：长上下文模型默认不再以 80% x window 为硬限。死循环防护由 stagnation 检测（连续 3 轮相同工具调用）和 hardMaxIterations（50 轮）兜底。Token 显示行 `📊 Token: 已用 X / Y` 的 Y 是软提示，不代表强制限制。

### LLM HTTP Timeout Config

系统属性 > 默认值：`yuforge.llm.connect.timeout.seconds`(60) / `yuforge.llm.read.timeout.seconds`(300) / `yuforge.llm.write.timeout.seconds`(60) / `yuforge.llm.call.timeout.seconds`(600)

SSE 流式下 readTimeout 是两次 read 间最大间隔，GLM-5.1 生成大段 reasoning 时可能长时间静默，所以放宽到 300 秒。
DeepSeek 流式调用默认使用 HTTP/1.1，避免部分 HTTP/2 网关在长 SSE 响应中重置 stream，表现为 `stream was reset: INTERNAL_ERROR`。
DeepSeek 当前不发送图片输入：`supportsImageInput()` 返回 false，含图片的 `ContentPart` 会在 OpenAI-compatible 请求序列化时替换成文本提示，避免不支持多模态的 DeepSeek API 收到 `image_url` block。

### Web Search Provider Config

1. `SEARCH_PROVIDER` 显式指定 `zhipu` / `serpapi` / `searxng`
2. 未指定时按 Key 自动判断：`GLM_API_KEY` → zhipu / `SERPAPI_KEY` → serpapi / `SEARXNG_URL` → searxng
3. 都没有 → zhipu 占位

各 provider：zhipu(`GLM_API_KEY` + 可选 `ZHIPU_SEARCH_ENGINE`) / serpapi(`SERPAPI_KEY`) / searxng(`SEARXNG_URL`)

### Web Fetch Security (NetworkPolicy)

scheme 白名单(http/https) / 主机黑名单(localhost/loopback/link-local/site-local) / 响应体上限 5MB / 超时 30s / 限流 30次/60s

### MCP Config

1. 用户级：`~/.yuforge/mcp.json`
2. 项目级：`.yuforge/mcp.json`
3. 按 server 名 merge，项目级覆盖用户级

格式兼容 Claude Code：`command` + `args` = stdio，`url` + `headers` = Streamable HTTP。内置变量：`${PROJECT_DIR}`、`${HOME}`；其他 `${VAR}` 从系统环境变量、系统属性、项目 `.env`、用户 `~/.env` 读取。
检测到 `STEP_API_KEY` 时自动内置 `step_search` 远程 MCP（显式同名配置优先），用于 Step 3.7 Flash 的 `web_search` / `web_fetch` 优先代理。

---

## Detailed Feature Behavior

### ReAct Mode

- 主入口：`Agent.java`
- 退出条件由 LLM 自决（不返回 tool_calls 即结束）
- `AgentBudget` 三种兜底：token 超预算 / 连续 3 轮相同调用 / 50 轮硬上限
- 流式输出 reasoning_content + content；inline ReAct 用固定高度 live thinking 区动态预览 reasoning，同一次输入只把完整 reasoning 引用块落到 transcript 一次；live 区只允许清理自己占用的行，避免覆盖旧输出
- inline 流式回答用低调 `▪` 标记起始，不再输出强标题；plain / 非流式兜底仍可使用传统 reasoning + answer 文本
- `TerminalMarkdownRenderer` 渲染 Markdown 表格时按终端列宽分配列宽，长内容在单元格内部换行；CJK 字符按显示宽度计算，避免表格行被终端自动折断后错位

### Long Context Engineering

- `ContextProfile` 按模型 window 连续派生预算，不再使用 short/balanced/long 行为分档
- GLM-5.1: 200k / DeepSeek V4: 1M / Agnes: 1M / StepFun: 256k / Kimi K2.6: 256k / FreeLLMAPI: 128k
- window ≥ 32k 时允许 MCP resources URI/描述索引；精确代码定位仍优先实时 glob/grep/read
- prompt caching：能力声明 + cached usage 解析
- 自动压缩高水位：`min(maxContextWindow - min(20k, window/4) - min(13k, window/8), maxContextWindow * 80%)`；200k 约 160k、1M 约 800k 触发。实际 history 阈值还会扣除工具 schema 估算。

### Memory System

- 两道压缩：
  1. `ContextCompressor` 压缩 shortTermMemory
  2. `ConversationHistoryCompactor` 压缩 conversationHistory（真正发给 LLM 的消息）
- 第二道治理先把旧的大型工具结果归档到有界 `ToolResultArtifactStore`，tool message 保留 `artifact_id`、preview 和恢复提示；`read_tool_artifact` 可恢复精确原文
- 归档仍不足时，切割在 user message 边界，保留最近 3 个 user 起算的完整尾部；摘要区使用全量分块 + Reduce 六段结构化工程检查点，不做固定字符截断
- 三条路径(ReAct/Plan/SubAgent)都接入第二道压缩
- `/compact` 可手动压缩当前 ReAct conversationHistory，不等待 token 阈值触发，保留最近 1 个 user 轮次；Plan/SubAgent 仍只走调 LLM 前的自动压缩
- 查询相关长期记忆追加到当前 user turn 的 `<relevant-memory>` 块，不修改稳定 system 前缀；工具 schema 按名称排序
- 长期记忆只通过 `/save` 或用户明确要求保存
- 长期记忆只保存跨会话稳定事实，不保存临时指令；默认项目级作用域，跨项目通用偏好才用 global
- 长期记忆当前采用关键词检索与元数据轻量加权；同分按 timestamp、id 稳定排序，空查询不返回所有记忆。`MemoryRetrievalGoldenSetTest` 固化相关性、项目隔离、空查询的最小回归集；Embedding hybrid retrieval 是后续可替换增强，接入前必须扩充该评测集并保持当前边界，不影响审计与作用域隔离。
- 每轮长期记忆注入都会生成仅本地可见的 `MemoryRetrievalTrace`：记录 query token 数、命中数、实际注入的 id/score/token 和因预算跳过的候选。`/memory status`、`/context` 与日志可查看它；trace 不含原始查询或记忆正文，且不会进入模型消息。
- `MemoryRetrievalStrategy` 是检索策略边界。默认 `KeywordMemoryRetrievalStrategy` 保持零外部依赖与离线回退；未来 Hybrid/Embedding 实现只替换候选排序，必须继续使用当前用户消息注入协议、`MemoryRetrievalTrace` 和 Golden Set。
- 长期记忆管理命令：`/memory list`、`/memory search <关键词>`、`/memory delete <id>`、`/memory clear`
- 默认 `/memory list/search/delete/clear` 只操作当前项目可见的 project + global 记忆；`/memory clear --global` 仅清 global，`/memory clear --all` 显式清全部，避免跨项目误删。
- `YUFORGE.md` 不是 `/save` 长期记忆：它是启动时注入 system prompt 的项目指令文件，适合团队共享、长期稳定、可进 git 的规则
- 加载顺序：`~/.yuforge/YUFORGE.md` → `YUFORGE.md` → `.yuforge/YUFORGE.md` → `YUFORGE.local.md` → `.yuforge/YUFORGE.local.md`
- `YUFORGE.md` 中独占一行的 `@relative/path.md` 会被展开；导入路径必须留在用户配置目录或项目根内，总注入内容按预算截断
- `/init` 生成精简 `YUFORGE.md`，只写 commands / project positioning / architecture / pitfalls / don'ts；已有文件默认不覆盖，`/init --force` 重写

### Multi-Agent

- 三角色：Planner / Worker(默认 2 个) / Reviewer
- 流程：规划 → 按依赖分配 Worker → Reviewer 审查 → 未通过重试(最多 2 次)
- SubAgent IOException 返回 ERROR 类型
- 所有子代理共享 ToolRegistry 和 MemoryManager

### HITL System

- 危险工具：write_file(中) / execute_command(高) / create_project(中) / revert_turn(高)
- 审批选项：y(批准) / a(全部放行) / n(拒绝) / s(跳过) / m(修改参数)
- fail-safe：连续 5 次无效输入判为 REJECTED
- 并发：requestApproval 整体 synchronized

### HITL Enhancement (Policy Layer)

- `PathGuard`：路径限定在项目根内（绝对路径外逃 / `..` 穿越 / 符号链接逃逸）
- `CommandGuard`：fast-fail 黑名单（sudo/rm -rf/mkfs/dd/fork bomb/curl|sh 等）
- `ResourceLimit`：write_file 5MB / execute_command 60s + 8KB 输出
- `AuditLog`：JSONL 字段 timestamp/tool/args/outcome/reason/approver/durationMs
- 拦截顺序：HitlToolRegistry → ToolRegistry → 策略层。用户无法批准策略拒绝的请求

### Parallel Tool Execution

- `executeTools()` 固定线程池并行，默认最多 4 个并发
- 返回结果保持原始顺序
- Agent/PlanExecuteAgent/SubAgent 三条路径都走 executeTools()
- `execute_command` 按平台选择 shell：Windows 使用 PowerShell，其他平台使用 bash；输出按 UTF-8 读取，实际 shell 进入 `<environment_context>`
- `glob_files`、Java grep fallback 与 ripgrep 输出均规范化为 `/` 分隔的项目相对路径，保证跨平台后续 `read_file` 参数一致

### Web Capabilities

- `web_search`：SearchProvider 接口，返回 SearchResult 列表
- `web_fetch`：NetworkPolicy → WebFetcher → HtmlExtractor，SPA/防爬墙返回空正文 + 边界提示
- 联网决策由模型通过原生 tool call 自主发起；Prompt 不包含 Freshness Policy，不强制 `web_search`。本地“当前项目/当前 README/当前文件/当前代码”仍作为代码库任务交给模型在工具 schema 中选择本地工具。
- StepSearch 优先级：当前模型 provider=`step` 且 model 以 `step-3.7-flash` 开头，并且自动/显式 `mcp__step_search__web_search` / `mcp__step_search__web_fetch` 已注册时，内置 `web_search` / `web_fetch` 会先代理到 StepSearch MCP；MCP 未就绪或返回不可用结果时回退原实现。
- JS 渲染 fallback 到 Chrome DevTools MCP

### MCP Protocol

- stdio + Streamable HTTP 双 transport
- 工具注册为 `mcp__{server}__{tool}`
- McpSchemaSanitizer 清洗 inputSchema
- 所有 mcp__ 工具默认走 HITL + AuditLog
- resources 双轨：虚拟工具 + @-mention 输入层
- CLI 首屏默认只等待 MCP 启动 8 秒，慢 server 后台继续初始化并保持 `starting`，用 `/mcp` / `/mcp logs <name>` 追踪
- notifications 路由：tools/list_changed → 工具全量替换，resources 变化 → cache 失效

### Chrome DevTools MCP

- 默认 server：chrome-devtools，`npx -y chrome-devtools-mcp@latest --isolated=true`
- `/browser connect`：切到 --autoConnect 复用登录态 Chrome
- `/browser connect <port>`：旧式 CDP 端口路径
- `/browser disconnect`：切回 isolated
- 敏感页面策略：改写型工具必须单步 HITL，不复用全部放行
- shared 模式 close_page 只允许关闭 YuForge 创建的 tab

### Skill System

- 三层加载：jar 内置 < 用户级 ~/.yuforge/skills/ < 项目级 .yuforge/skills/
- frontmatter：name(必填) / description(必填,<=500) / version / author / tags
- system prompt 索引段注入到三处提示词末尾，上限 20 个 / 4KB
- load_skill 工具把 SKILL.md 正文(5KB 截断)写入 SkillContextBuffer
- buffer 一次性消费，最多 3 个 skill body

### TUI (v16.1 Renderer Architecture)

- 三个实现：InlineRenderer(默认) / LanternaRenderer / PlainRenderer
- 环境变量：`YUFORGE_RENDERER=inline|lanterna|plain`
- `YUFORGE_TUI=true`(旧) → lanterna + deprecation 提示
- `YUFORGE_NO_STATUSBAR=true`：禁用底部状态栏
- `NO_COLOR=1`：禁用 ANSI 颜色
- 当前开屏 Banner 是无右侧盒线边框的简洁布局，避免 ANSI/CJK 字宽导致竖线错位
- InlineRenderer 复用 JLine 4 的编辑能力，默认提示符是 `* `，右提示显示 `message / @path / @image`
- BottomStatusBar 是 JLine `Status` 托管的底部 dock：由 JLine 负责滚动区域和状态行位置，不再手写 `\n`、`moveUp`、`CLEAR_TO_EOS` 或绝对光标行号；dock 上层展示 YOLO/HITL 与 MCP/Skill 摘要，下层展示 model、phase、ctx、token、cost、elapsed 与 cwd。关键字段可用 JLine `AttributedString` 做克制彩色高亮，但纯文本格式和列宽裁剪仍要稳定。`ctx` 只表示当前仍会带入下一轮请求的上下文估算，`in/out/cache` 表示最近任务调用统计。
- `/clear` 清空当前 ReAct conversationHistory、shortTermMemory、待注入 SkillContextBuffer、会话 TODO 和 ToolResultArtifactStore，并重建不含上一轮检索记忆的 system prompt；长期记忆条目保留，后续只会按新查询重新检索注入。
- `/compact` 手动压缩当前 ReAct conversationHistory，压缩期间显示动态 activity 面板，成功后刷新底部 ctx；不会清空 shortTermMemory、长期记忆或待注入 SkillContextBuffer。
- `/export` 导出当前 ReAct conversationHistory 为 Markdown 到 `~/.yuforge/exports/session-*.md`；包含完整 system prompt，便于检查 LLM 实际接收前的指令，命令不接受路径参数。
- 普通任务和斜杠命令提交后都会以 `>` 暗色整行块回写原始输入，避免 JLine accept 后清掉编辑行导致结果区看不到刚执行的命令
- InlineRenderer 不使用独立 JLine `Display.update()` 维护 thinking 临时区；真实终端验证发现独立 Display 会在 transcript/status 输出后从错误位置向上清屏。当前实现用固定高度 live 区重写自身行，content/tool 边界先清理 live 区再追加 transcript。
- 交互期输出优先走 `Renderer.stream()`；`Main`、`PlanExecuteAgent`、`Planner`、`AgentOrchestrator` 都可接收同一个 renderer 输出流，避免绕过 inline renderer 直接写 stdout
- `CodeIndex` 通过 `ProgressListener` 上报索引开始 / 文件数量 / 进度 / 完成或失败，`/index` 绑定当前 renderer 输出流；内部异常细节写 logger

### LSP Diagnostics (Phase 17)

- write_file 成功后对 Java 文件做 JavaParser 语法诊断
- 诊断作为合成 user message 注入下一轮 LLM 请求
- `YUFORGE_LSP_ENABLED=false` 关闭

### Git Side-History Snapshot (Phase 18)

- side-git 在 ~/.yuforge/snapshots/ 维护独立仓库（JGit，不依赖系统 git）
- pre-turn 同步，post-turn 异步
- revert_turn 纳入 HITL/AuditLog，恢复前先创建 pre-restore 快照

### Prompt Layering (Phase 19)

- system prompt 组装顺序：base → personality → mode → approval → context_mgmt → handoff → project_context → skills
- `project_context` 只承载稳定的 `YUFORGE.md` 项目记忆；`RuntimeContextFormatter` 把时间戳、日期/时区、workspace、OS、shell、相关长期记忆与 MCP resource index 放进当前 user turn
- ReAct、Plan task、SubAgent、Planner 均使用相同的 `<environment_context>`；运行期内容变化不重写 system prompt
- 覆盖优先级：jar 内置 < 用户级 ~/.yuforge/prompts/ < 项目级 .yuforge/prompts/
- 必要校验：base.md 和最终 prompt 必须包含 `## Language`

### Tool Failure Recovery

- `ToolResultDiagnostic` 从工具原始文本、timeout 标志和命令退出码生成稳定错误码：`FILE_NOT_FOUND` / `INVALID_ARGUMENT` / `TIMEOUT` / `POLICY_DENIED` / `PERMISSION_DENIED` / `COMMAND_FAILED` / `UNKNOWN_TOOL` / `INTERRUPTED` / `TOOL_ERROR`
- `ToolAttemptTracker` 生命周期限定在单次 ReAct run、单个 Plan task 或单次 SubAgent execute；调用签名使用“工具名 + 规范化 JSON 参数”，JSON 字段顺序不影响计数
- 首次失败提供恢复建议；同错误第二次要求改变参数或工具；第三次禁止原样重试并要求报告阻塞
- 成功的首次工具结果保持原格式，避免无意义 token 膨胀；失败或重复调用才使用结构化 envelope
- Java stack trace、敏感本地信息留在 debug/audit log，不直接回灌模型

### Session TODO Work Memory

- `rewrite_todo_list` / `update_todo_status` 仅用于复杂、多步骤任务；简单问答或单步修改不应制造 TODO 噪音
- TODO 由 `TodoTracker` 在进程内保存，不落 SQLite、不进入长期记忆、不替代 Plan DAG 或 DurableTask
- 清单作为 `<todo_list>` 注入后续 user turn，避免改变稳定 system prompt；底部 dock phase 显示 `TODO completed/total`
- `/clear` 同时清理对话历史、当前 TODO 清单和会话工具归档

### Session Checkpoint + Resume

- `/checkpoint` 将当前 ReAct 历史与会话 TODO 保存到 `~/.yuforge/sessions/`；可用 `-Dyuforge.session.dir` 或 `YUFORGE_SESSION_DIR` 覆盖目录
- `/session` 列出最近 10 个 checkpoint；`/resume <session_id>` 只允许恢复当前 project key 相同的会话
- checkpoint 保存 user/assistant 文本历史和 TODO，但不保存系统提示、图片 payload、任何 tool result 原文或 ToolResultArtifactStore 原文；恢复时重新构建当前 system prompt，工具结果会明确标记为不可恢复
- 默认最多保留 30 个 checkpoint，写入采用临时文件 + 原子替换；文件系统不支持原子 move 时回退常规替换

### Async Tasks + Runtime API (Phase 20)

- DurableTaskManager(SQLite) / CLI: /task, /task list, /task add, /task cancel, /task log
- Runtime API: `serve --http --port 8080`，仅 127.0.0.1，需 API Key
- 端点：POST /v1/threads / POST /v1/threads/{id}/turns / GET /v1/threads/{id}/events

### Image Input (Phase 21)

- ContentPart 支持图片 block（base64 + mimeType）
- ImageProcessor：铺白底/缩放 2000x2000/压缩 5MB
- 输入：`@image:file:///path.png` / `@image:/path.png` / `@image:relative.png`
- GLM-5V-Turbo 通过 `/model glm-5v-turbo` 切换
- Provider 通过 `supportsImageInput()` 声明是否接收图片；不支持时保留文字上下文并省略图片 payload
- 历史 image payload 替换为文本占位，避免旧截图消耗上下文

---

## Core File Descriptions

### Main.java
CLI 入口 / Banner / .env 读取 / 日志初始化 / 模式切换 / JLine raw mode

### Agent.java
ReAct 主循环 / 对话历史 / 工具调用与结果回灌

### PlanExecuteAgent.java
规划后执行 / 计划审阅 / DAG 任务执行 / 并行批次 / 失败重规划

### AgentOrchestrator.java
Multi-Agent 编排器 / 三角色管理 / 按依赖分配 / 审查重试

### SubAgent.java
可配置角色子代理 / 独立对话历史 / Worker 用工具、Planner/Reviewer 不用

### Planner.java
LLM 生成计划 JSON / 简单任务最小计划 / 重编号 task_1..N / 依赖计算

### ExecutionPlan.java
DAG 拓扑排序 / 可执行任务判定 / 进度可视化

### ToolRegistry.java
核心内置工具 + MCP 动态工具 / executeTools() 并行入口 / ToolInvocation / ToolExecutionResult。`read_tool_artifact` 是会话级只读恢复工具。工具定义按名称排序，避免无意义的 schema 顺序变化破坏 prompt cache。代码理解默认路径是 `glob_files` / `grep_code` / `read_file` 现用现查，`grep_code` 优先走 ripgrep 并按 `max_results` / `head_limit` / `max_chars` 渐进返回，`search_code` 保留为 RAG 语义辅助。确定性搜索链路的回归样例见 `docs/code-search-golden-set.md`。

### MCP Package
McpServerManager / McpClient / JsonRpcClient / StdioTransport / StreamableHttpTransport / McpSchemaSanitizer / resources/ / mention/ / notifications/

### TUI Package
TuiBootstrap / LanternaWindow / TuiSessionController / pane/ / hitl/ / history/ / highlight/

### LLM Clients
- GLMClient：glm-5.1，glm-5v 开头切多模态接口
- DeepSeekClient：deepseek-v4-flash，thinking + tool calls 带回 reasoning_content
- StepClient：step-3.5-flash，可通过 STEP_BASE_URL 切通道
- KimiClient：kimi-k2.6，thinking + tool calls 带回 reasoning_content
- FreeLlmApiClient：auto，默认 http://localhost:5173/v1，OpenAI-compatible 本地网关；可用 `/config provider freellmapi ...` 写入配置后 `/model freellmapi` 切换
- XfyunMaaSClient：Qwen3.6-35B-A3B，默认 https://maas-api.cn-huabei-1.xf-yun.com/v2，OpenAI-compatible 讯飞星辰 MaaS；可用 `/config provider xfyun ...` 写入配置后 `/model xfyun` 切换。`model` 必须使用 MaaS 服务管控页展示的 modelId；微调模型可配置 `--lora-id <resourceId>`，作为 HTTP header `lora_id` 发出；该 provider 不发送 YuForge 内置 tools。
- AgnesClient：agnes-2.0-flash，默认 https://apihub.agnes-ai.com/v1，OpenAI-compatible Agnes AI，默认 1M context window；可用 `/config provider agnes ...` 写入配置后 `/model agnes` 切换，支持流式输出和 tools。

---

## .env.example Reference

```bash
GLM_API_KEY=your_api_key_here
# GLM_MODEL=glm-5.1
# GLM_MODEL=glm-5v-turbo
# DEEPSEEK_API_KEY=your_deepseek_api_key_here
# DEEPSEEK_MODEL=deepseek-v4-flash
# STEP_API_KEY=your_step_api_key_here
# STEP_MODEL=step-3.5-flash
# STEP_BASE_URL=https://api.stepfun.com/v1
# KIMI_API_KEY=your_kimi_api_key_here
# MOONSHOT_API_KEY=your_moonshot_api_key_here
# KIMI_MODEL=kimi-k2.6
# KIMI_BASE_URL=https://api.moonshot.ai/v1
# FREELLMAPI_API_KEY=your_freellmapi_unified_key_here
# FREELLMAPI_MODEL=auto
# FREELLMAPI_BASE_URL=http://localhost:5173/v1
# AGNES_API_KEY=your_agnes_api_key_here
# AGNES_MODEL=agnes-2.0-flash
# AGNES_BASE_URL=https://apihub.agnes-ai.com/v1
# XFYUN_MAAS_API_KEY=your_xfyun_maas_api_key_here
# XFYUN_MAAS_MODEL=Qwen3.6-35B-A3B
# XFYUN_MAAS_BASE_URL=https://maas-api.cn-huabei-1.xf-yun.com/v2
# XFYUN_MAAS_LORA_ID=0
EMBEDDING_PROVIDER=ollama
EMBEDDING_MODEL=nomic-embed-text:latest
EMBEDDING_BASE_URL=http://localhost:11434
# EMBEDDING_API_KEY=your_api_key_here
# YUFORGE_LOG_LEVEL=INFO
# YUFORGE_LOG_DIR=/Users/yourname/.yuforge/logs
# YUFORGE_LOG_MAX_HISTORY=7
# YUFORGE_LOG_MAX_FILE_SIZE=10MB
# YUFORGE_LOG_TOTAL_SIZE_CAP=100MB
# YUFORGE_SNAPSHOT_ENABLED=true
# YUFORGE_SNAPSHOT_MAX=50
# YUFORGE_SNAPSHOT_EXCLUDES=.git,.yuforge/snapshots,target,node_modules,dist,.idea,*.class,*.jar
# YUFORGE_SNAPSHOT_DIR=/Users/yourname/.yuforge/snapshots
# YUFORGE_TUI=true
# NO_TUI=true
```

---

## Test Coverage Summary

测试覆盖偏向：解析、计划结构、RAG 核心、Multi-Agent 编排、HITL 策略、策略层拦截、MCP 协议、资源输入层、长上下文策略与 Skill 加载。

不覆盖：真实 LLM 联调、真实 Embedding API、真实 MCP server 联调、终端完整手工体验。

完整测试类列表：CliCommandParserTest / MainBrowserCommandTest / PlanReviewInputParserTest / MainInputNormalizationTest / ExecutionPlanTest / MemoryEntryTest / ConversationMemoryTest / LongTermMemoryTest / MemoryRetrieverTest / MemoryManagerTest / ExplicitMemoryHintsTest / ContextProfileTest / PlanExecuteAgentTest / AgentMemoryHintTest / AgentRoleTest / AgentMessageTest / AgentOrchestratorTest / EmbeddingClientTest / SearchResultTest / NetworkPolicyTest / HtmlExtractorTest / WebFetcherTest / SearchProviderFactoryTest / ZhipuSearchProviderTest / VectorStoreTest / CodeChunkerTest / CodeAnalyzerTest / CodeIndexTest / ApprovalPolicyTest / ApprovalResultTest / HitlToolRegistryTest / TerminalHitlHandlerTest / ToolRegistryTest / BrowserSessionTest / BrowserConnectivityCheckTest / SensitivePagePolicyTest / BrowserGuardTest / McpSchemaSanitizerTest / McpConfigLoaderTest / JsonRpcClientTest / McpToolBridgeTest / McpResourceCacheTest / AtMentionParserTest / AtMentionExpanderTest / AtMentionCompleterTest / NotificationRouterTest / PathGuardTest / CommandGuardTest / AuditLogTest / SkillFrontmatterParserTest / SkillRegistryTest / SkillStateStoreTest / SkillBuiltinExtractorTest / SkillContextBufferTest / SkillIndexFormatterTest / LoadSkillToolTest / SkillCommandHandlerTest
