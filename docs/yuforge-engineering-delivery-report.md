# YuForge 工程化优化与交付报告

> 版本：v16.1.0  
> 定位：面向真实代码库任务的 Java Code Agent CLI  
> 本文只记录已落地能力与明确边界；路线图中的设想不视为已交付。

## 1. 交付结论

YuForge 已从“调用大模型的命令行 Demo”收敛为可在真实项目中工作的 Code Agent：它能理解工作区、检索与修改代码、执行受控命令、给出验证证据、接入 MCP、保存/恢复会话，并在 Windows Terminal 下提供稳定的长会话 transcript。

本阶段的核心原则是三个“先于功能”的约束：

1. **上下文和缓存经济性优先**：system prompt 必须稳定，动态信息不能破坏前缀缓存。
2. **工具与外部内容默认不可信**：读取网页、MCP、文件内容不等同于获得执行或记忆写入授权。
3. **终端正确性优先于炫技式 UI**：缩放、全屏、CJK 字符和异步输出下，不能篡改已呈现的历史。

## 2. 解决的问题与架构取舍

```text
用户输入
  ├─ CLI：命令补全 / 工作区信任 / 本地 @path 与 MCP resource 展开
  ├─ Runtime Context：时间、工作目录、Shell、相关记忆（当前 user turn）
  └─ Agent
       ├─ ReAct（默认）
       ├─ Plan-and-Execute（/plan）
       └─ Multi-Agent（/team）
            └─ ToolRegistry → HITL → PathGuard / CommandGuard → 本地或 MCP 工具
                 ├─ Side-Git Snapshot / revert
                 ├─ Memory / Context Compaction / Artifact Store
                 └─ Renderer：追加式、可审计 transcript
```

三条 Agent 路径共享 ToolRegistry、记忆、上下文治理和安全策略。这样能避免“默认模式安全、Plan 或子 Agent 绕过安全”的架构分叉。

## 3. 上下文、Prompt Cache 与记忆

### 3.1 稳定 Prompt 前缀

`PromptAssembler` 按固定顺序拼接 system prompt：

```text
base → personality → mode → approval → context management → handoff
     → project context → skills
```

时间、时区、工作目录、操作系统、Shell、动态检索记忆和 MCP resource 索引不再写入 system prompt，而是放进本轮 user message。这样固定前缀保持一致，既更利于 provider 的 prompt caching，也避免每轮动态字段导致缓存失效。

工具 schema 按名称稳定排序；相关长期记忆以 `<relevant-memory>` 附加到当前 user turn，而不是重写 system message。

### 3.2 不用简单滑动窗口

简单“只保留最近 N 条”会破坏历史前缀，也可能切断 assistant tool call 与 tool result 的配对；更严重的是会丢失关键工具结果，导致 Agent 反复调用同一工具。

YuForge 的上下文治理分层处理：

1. 达到高水位时，优先将旧的大型 `tool_result` 归档到有界 `ToolResultArtifactStore`。
2. 历史中保留协议安全占位符、`artifact_id` 与摘要；模型需要原文时使用只读 `read_tool_artifact` 精确取回。
3. 仍超预算时，按 user turn 边界保留最近完整事务，将旧历史通过 map-reduce 压为结构化工程检查点，而不是按字符截断。
4. `/compact` 可显式压缩当前会话，保留最近一轮与必要的 tool-call/tool-result 边界。

自动压缩阈值同时扣除摘要预留、响应 buffer 和工具 schema 预算，避免“历史估算未超限、实际请求却超限”。

### 3.3 分层记忆和跨会话恢复

- `YUFORGE.md`：项目共享、长期稳定的协作规则；启动自动加载，可通过 `/init` 生成。
- `/save` 长期记忆：仅由本轮用户明确授权写入，默认项目作用域；支持 list/search/delete/clear 审计闭环。
- 短期记忆：服务于当前任务，不替代会话历史治理。
- `/checkpoint`：保存可恢复会话；`/session` 列表；`/resume <id>` 恢复当前项目的 checkpoint，并回放可读的 user/assistant transcript。

恢复不持久化图片和大型工具 artifact 原文，也不会重新使用旧 system prompt；恢复始终以当前项目、当前 system prompt 为准，避免配置漂移和敏感原文长期落盘。

## 4. 工具可靠性与真实开发闭环

### 4.1 工具使用边界

代码理解默认使用 Claude Code 式“实时探索”：

1. `glob_files` 找候选文件；
2. `grep_code` 精确定位符号/字符串（优先 `ripgrep`，不可用时回退 Java 扫描）；
3. `read_file` 按需读文件或行段；
4. `search_code` 仅用于语义模糊、跨知识或常规搜索无果的辅助检索。

工具结果有大小预算、`partial` 标记和 `suggested_reads`，引导模型缩小范围，而不是把整个仓库无选择地塞进上下文。

### 4.2 改动后必须有证据

`ChangeVerificationTracker` 记录本轮实际发生的工作区改动以及后续的验证操作。若模型写入/补丁/创建项目后没有构建、测试、服务就绪或回读证据，最终输出会明确标记“已修改但未验证”，不能把“已写文件”伪装成“问题已修复”。

受控后台开发进程支持启动、就绪等待和状态诊断，目标是让 Agent 能完成“修改 → 启动/测试 → 查看结果”的闭环，而不是只会生成补丁。

### 4.3 Side-Git 安全快照

每轮变更前创建 Side-Git 快照，`revert_turn` 可回退本轮操作；快照失败会降级报告，不会因用户没有 Git 权限而中断纯聊天或只读任务。已有未提交改动默认视为用户资产，Agent 不会擅自 reset 或覆盖。

## 5. 安全防线：提示词注入、HITL 与路径策略

### 5.1 提示词注入

防御不只依赖“请忽略外部指令”的 system prompt：

- 网页、搜索和 MCP 返回统一包裹为 `<untrusted_external_content>`，正文转义。
- 外部内容不能授予写文件、执行命令、创建项目、保存记忆、回退或调用 MCP 的权限。
- 只要本轮读取过不可信内容，后续高风险操作必须逐次 HITL 确认，不能被“全部放行”缓存绕过。
- 输出侧有 system-prompt 泄露防护；直接要求披露 system prompt 不应被正常回答路径执行。

### 5.2 工具策略顺序

```text
HitlToolRegistry → ToolRegistry → PathGuard / CommandGuard
```

用户批准不能越过策略拒绝。`PathGuard` 将读写限定在项目根内；`CommandGuard` 是辅助防护而非沙箱。工作区首次进入时提供 trust gate，用户只有在确认可信后才能加载项目级配置、hooks 和执行策略。

当前能力不是容器/VM 沙箱，也没有通用网络出口控制或 DLP；这些必须如实视为后续工作。

## 6. MCP 与跨平台可用性

### 6.1 MCP 生命周期

用户级 `~/.yuforge/mcp.json` 与项目级 `.yuforge/mcp.json` 合并加载；工具动态注册为 `mcp__{server}__{tool}`。`/mcp` 展示最新状态，`/mcp logs <name>` 查看启动日志。

首屏和输入框不会等待 MCP：banner 先出现，服务随后静默后台初始化。为避免误导，首屏在尚未完成时显示“`MCP N configured · starting in background`”，而非虚假的 `0/N · 0 tools`；完成后的真实 server/tool 数以 `/mcp` 为准。

### 6.2 Windows 的 `npx` 兼容

PowerShell 能找到 `npx`，不代表 Java `ProcessBuilder` 能执行裸命令。stdio transport 在 Windows 会解析 `npx` 到可执行的 `npx.cmd`，因此 Chrome DevTools MCP 等 Node MCP server 能从 CLI 正确启动。

这解决的是进程启动差异，不是 MCP server 本身的安装或网络问题；若 `/mcp` 仍报错，应先使用 `/doctor` 检查 Node/PATH，再看 `/mcp logs`。

## 7. CLI 体验与终端渲染

### 7.1 最终选择：append-only transcript

Windows Terminal 在缩放、全屏、切换标签时会重排 scrollback。依赖 ANSI 相对光标、右提示或动态底部状态栏的 UI 可能在旧帧留下残影、重复 `message / @path / @image`，甚至覆盖用户输入。

默认 inline CLI 因此采用：

- 输入提示为 `> `；提交后的用户输入保留在历史中；
- 不使用 JLine right prompt；
- 不使用动态 bottom dock；
- `Thinking…` 作为独占、追加的稳定事件行，绝不覆盖已提交输入；
- 工具调用使用可折叠、单行摘要，`Ctrl+O` 只在末尾追加详情；
- 对 Windows 兼容性不稳定的 emoji 进行终端输出降级，避免显示成问号。

这不是降低产品体验，而是对终端能力边界的工程取舍：可审计、无乱序、可滚动回看优先于看似“实时”的浮动组件。本地调试仍可显式开启实验性 dock：`-Dyuforge.inline.bottom-dock=true`。

### 7.2 交互能力

- 输入单独的 `/` 时展示不超过 8 个高频命令及说明；继续输入按前缀过滤，Tab 用于补全。
- `/init` 基于有界本地扫描生成项目级 `YUFORGE.md`，识别 Java/Node 结构、入口、构建配置和资源目录；它是快速项目导航，不替代完整代码审查。
- `/doctor` 只读检查 Java、Git、ripgrep、Maven/Node、当前 API key 与 MCP 摘要，不执行构建、不安装依赖、不发网络请求。
- `/history` 管理本机输入历史；`/checkpoint`、`/session`、`/resume` 提供跨终端会话恢复。
- ESC 语义清晰：输入期清空编辑缓冲，运行期请求取消。

## 8. 模型、运行时与成本感知

多 provider 支持 GLM、DeepSeek、Step、Kimi、FreeLlmAPI、讯飞 MaaS 和 Agnes。DeepSeek V4/Kimi thinking 模式会按协议把 tool-call 对应 `reasoning_content` 带回下一轮；其余 provider 默认仅记录必要日志，不将原始推理刷到 transcript。

DeepSeek 流式调用默认强制 HTTP/1.1，规避部分网络/网关上的 HTTP/2 stream reset。动态 runtime context 放入 user turn，工具调用计数与标准化错误诊断则促使 Agent 在重复失败时改变参数、换工具或停止重试，而非陷入循环。

## 9. 验证、安装与日常验收

本阶段为 CLI、工具、MCP transport、项目初始化、工作区信任、渲染、快照与安全策略补充了回归测试。最近一次修改已验证：

```powershell
mvn test "-Dtest=MainInputNormalizationTest" -DskipTests=false
mvn package
```

结果：31 项 `MainInputNormalizationTest` 通过，shaded jar 构建成功。

推荐完整验收：

```powershell
mvn test -Pquick
mvn test -Pphase16-smoke
mvn test -DskipTests=false
```

本地安装和启动：

```powershell
cd D:\up\yuforge-main
powershell -ExecutionPolicy Bypass -File scripts\install.ps1

cd C:\code\your-project
yuforge
```

建议人工检查四类场景：首次工作区信任、MCP 后台启动后 `/mcp`、在 Windows Terminal 缩放/全屏/切标签、修改代码后的测试或回读验证。

## 10. 已知边界与后续优先级

已交付并不意味着完整复刻 Codex 或 Claude Code。以下能力尚未交付，不应在简历或 README 写成“已有”：

- 容器/VM 级命令沙箱、通用网络 egress policy、通用 DLP；
- MCP OAuth、sampling、server 自动重启与更完整的 recovery；
- 用户插话后让工具后台续跑的事件队列状态机；
- 终端跨平台的复杂动态布局。默认 CLI 选择 append-only，而非持续重绘。

下一步若继续投入，优先级应是：真实项目端到端案例与回归评测、安装/配置失败诊断、工具成功率与验证率遥测；不建议为追求表面“像某个产品”重新引入不稳定的终端动画。

## 11. 面试表达

“YuForge 的目标不是把模型包一层 CLI，而是完成真实代码库任务的可控闭环。我把 Prompt Cache 一致性当作架构约束：稳定规则留在 system prefix，时间、工作区和检索记忆放进当前 user turn；上下文超限时先归档工具结果，再按事务边界压缩，避免滑动窗口丢失关键结果。安全上我把外部网页和 MCP 都当不可信数据，工具写操作还要经过 HITL 和路径策略。最后，Windows Terminal 的缩放会破坏相对光标重绘，所以我选择追加式 transcript，而不是为了视觉效果牺牲可读性和审计性。每次改动后，系统会区分‘已经修改’和‘已经验证’，让 Agent 更接近真实开发流程。”

## 12. 关联设计文档

- [上下文与记忆工程](context-memory-engineering.md)
- [Prompt 分层](phase-19-prompt-layering.md)
- [提示词注入防护](prompt-injection-defense.md)
- [取消与中断边界](cancellation-and-interruption.md)
- [MCP Core](phase-10-mcp-core.md) / [MCP Advanced](phase-11-mcp-advanced.md)
- [Chrome DevTools MCP](phase-13-chrome-devtools-mcp.md)
- [CLI 渲染工程报告](cli-rendering-engineering-report.md)
- [JLine 交互升级](phase-22-jline-interaction-upgrade.md)
