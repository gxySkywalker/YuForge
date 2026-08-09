# YuForge

> A production-minded Java Code Agent CLI for real codebases.

面向真实代码库任务的 Java Code Agent CLI：让 Agent 完成 **理解 → 规划 → 修改 → 验证** 的开发闭环，而不只是生成一段代码或调用一次工具。

![Java](https://img.shields.io/badge/Java-17%2B-ED8B00?logo=openjdk&logoColor=white)
![Maven](https://img.shields.io/badge/build-Maven-C71A36?logo=apachemaven&logoColor=white)
![MCP](https://img.shields.io/badge/protocol-MCP-7C3AED)
![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-4B8BBE)

[快速开始](#快速开始) · [核心能力](#核心能力) · [安装与发布](#安装与发布) · [架构文档](#架构设计文档) · [贡献与验证](#测试策略)

<img src="docs/images/cli-command-completion.png" alt="YuForge CLI command discovery" width="900" />

## 为什么是 YuForge

| 真实问题 | YuForge 的处理方式 |
| --- | --- |
| 长任务越跑越长，滑动窗口会丢失工具结果 | 大工具结果归档 + 可按 `artifact_id` 恢复；按 user turn 进行结构化压缩 |
| 动态 system prompt 降低 Prompt Cache 命中 | 稳定 system prefix；时间、工作目录、记忆等动态信息进入当前 user turn |
| 网页/MCP 内容可能注入恶意指令 | 不可信来源标记 + HITL + PathGuard/CommandGuard + 记忆写入显式授权 |
| Agent 修改了代码却无法证明完成 | 改动后区分“已修改”和“已验证”；构建、测试、ready 或回读才形成证据 |
| Windows Terminal 缩放后终端错乱 | 默认 append-only transcript，不依赖易残影的相对光标重绘、右提示或动态 dock |

## 核心能力

- **Code Agent Runtime**：ReAct 为默认执行路径；复杂任务可切换 `/plan` 或 `/team`。
- **真实代码库探索**：`glob_files → grep_code → read_file` 的实时探索路径，RAG 仅作为模糊语义检索补充。
- **长上下文与记忆**：工具结果归档恢复、结构化压缩、项目级 `YUFORGE.md`、长期记忆和 checkpoint 会话恢复。
- **安全工具调用**：HITL、工作区信任、PathGuard、CommandGuard、审计日志与 Prompt Injection 防护。
- **MCP 与浏览器**：stdio / Streamable HTTP MCP、动态工具与 resources、Chrome DevTools MCP。
- **跨平台 CLI**：JLine 交互、`/` 命令发现与 Tab 补全、可取消任务、Windows/macOS/Linux 安装入口。

## 快速开始

### 1. 安装

**发布版（推荐）**：在 [GitHub Releases](../../releases) 下载与你的平台对应的安装脚本后执行。每个 Release 都包含 shaded jar、SHA-256 校验文件和 Windows/macOS/Linux 安装脚本；安装后会把 `yuforge` 加入当前用户 PATH。

Windows PowerShell：

```powershell
# 安装最新发布版
irm https://github.com/gxySkywalker/YuForge/releases/latest/download/install.ps1 | iex

# 打开一个新终端
yuforge
```

macOS / Linux：

```bash
# 安装最新发布版
curl -fsSL https://github.com/gxySkywalker/YuForge/releases/latest/download/install.sh | sh

# 重新打开终端
yuforge
```

**从源码安装**：

```powershell
git clone https://github.com/gxySkywalker/YuForge.git
cd YuForge
mvn clean package
powershell -ExecutionPolicy Bypass -File scripts\install.ps1
```

### 2. 配置 API Key

前置条件只有两个：**Java 17+** 和 **至少一个模型 API Key**。不需要同时配置所有 provider；例如只使用 DeepSeek 时，只配置 `DEEPSEEK_API_KEY` 即可。

推荐按作用域二选一：

| 方式 | 适用场景 | 位置 |
| --- | --- | --- |
| 项目级 `.env` | 不同项目使用不同 Key；本地开发 | 当前项目根目录的 `.env` |
| 用户环境变量 | 所有项目复用同一 Key；Release 安装用户 | 用户环境变量，重新打开终端后生效 |

**项目级 `.env`（推荐）**：在你准备使用 YuForge 的项目根目录新建 `.env`，不要提交到 Git。

```dotenv
DEEPSEEK_API_KEY=your_api_key
DEEPSEEK_MODEL=deepseek-v4-flash
```

Windows 设置为当前用户环境变量：

```powershell
[Environment]::SetEnvironmentVariable('DEEPSEEK_API_KEY', 'your_api_key', 'User')
[Environment]::SetEnvironmentVariable('DEEPSEEK_MODEL', 'deepseek-v4-flash', 'User')
# 关闭并重新打开终端后生效
```

支持的环境变量包括 `GLM_API_KEY`、`DEEPSEEK_API_KEY`、`STEP_API_KEY`、`KIMI_API_KEY`、`FREELLMAPI_API_KEY`、`XFYUN_MAAS_API_KEY` 和 `AGNES_API_KEY`。YuForge 的优先级为：`~/.yuforge/config.json` 中显式配置 → 系统/用户环境变量 → 当前项目 `.env` → 用户目录 `~/.env`。密钥不会显示在 `/doctor`、MCP 日志或终端输出中。

### 3. 在项目目录中启动

```powershell
cd C:\code\your-project
yuforge
```

首次进入一个工作区会展示信任确认页。确认后，再输入任务即可；输入 `/` 查看常用命令，Tab 可补全参数。

```text
> 分析当前项目的架构，并给出本地启动步骤
> /init
> 修复登录接口空指针，补充测试并验证
> /mcp
```

## 安装与发布

仓库通过 GitHub Actions 在推送 `v*` tag 时自动构建并发布：

```text
tag → Maven shaded jar → SHA-256 → Windows install.ps1 / Unix install.sh → GitHub Release
```

发布步骤：

```bash
mvn test -Pquick
git tag v1.0.0
git push origin v1.0.0
```

发布完成后，Release 中的安装脚本已写入对应 jar 的不可变下载地址；用户无需安装 Maven，只需 Java 17+，安装后可以在任意项目目录运行 `yuforge`。

## 测试策略

日常开发不需要每次都跑全量测试。`mvn clean package` 默认跳过测试，优先产出可手工验收的 jar；需要回归时按改动范围选择：

```bash
# 第 16 期终端 / TUI / inline renderer 冒烟
mvn test -Pphase16-smoke

# 常规快速回归，跳过外部进程 / 网络超时 / 命令超时类慢测试
mvn test -Pquick

# 代码搜索 deterministic golden set
mvn test -Dtest=CodeSearchGoldenSetTest -DskipTests=false

# Prompt Injection 防御回归集
mvn test -Dtest=PromptInjectionDefenseTest,SystemPromptLeakGuardTest,AbstractOpenAiCompatibleClientImageInputTest,HitlToolRegistryTest,ToolRegistryTest -DskipTests=false

# 发版或大范围重构前再跑全量
mvn test -DskipTests=false
```

## 架构设计文档

- [工程化优化与交付报告](docs/yuforge-engineering-delivery-report.md)：面向 GitHub 展示与技术面试的总览，覆盖上下文、记忆、安全、工具闭环、MCP、CLI 稳定性、验证证据及未交付边界。
- [简历项目描述](docs/resume-yuforge-project.md)：可直接用于 AI Agent 岗和 Java 后端岗位的项目简介、技术栈、职责要点与面试展开话术。
- [上下文与记忆工程设计说明](docs/context-memory-engineering.md)：Prompt Cache 约束、长上下文治理、工具结果归档恢复、会话 checkpoint、长期记忆检索与评测基线。
- [Prompt Injection 防御与回归矩阵](docs/prompt-injection-defense.md)：直接/间接/记忆注入的防线、自动化用例和已知边界。
- [取消与打断机制](docs/cancellation-and-interruption.md)：同步 SSE 请求取消链路、验证方式，以及事件队列打断的后续边界。

安全边界说明：当前已交付 Prompt Injection 的提示词、来源、授权、审批和输出侧防线；容器/VM 沙箱、命令网络出口控制与通用 DLP 仍是后续增强，详见上面的防御文档。

取消语义：inline CLI 的 `ESC`、TUI `/cancel` 与微信取消会中断后续 Agent/工具循环，并向 OpenAI-compatible provider 的同步 SSE HTTP 请求传播 `Call.cancel()`；网络层仍以 provider 对连接关闭的响应速度为准。OpenAI-compatible SSE 正文使用 128 原始字符安全保留窗口后即持续显示，不再等待整段响应完成；该窗口用于在输出前阻断连续 system prompt 泄露片段。

代码改动采用“证据优先”交付：Agent 写入或补丁后应运行项目匹配的构建、测试或诊断；配置/文档至少回读，启动服务须等待 ready 信号。ReAct 结束时会显示本轮 `✅ 验证` 或 `⚠️ 验证` 状态；后者表示改动尚未获得真实验证证据，不能当作已完成。

## 演进历程

### 第一期：ReAct Agent CLI

- 单轮对话驱动的 `ReAct` 循环
- 支持工具调用：读文件、精确补丁、列目录、文件 glob、代码 grep、短命令与受控后台开发服务、创建项目、RAG 语义辅助检索、联网搜索、MCP 动态工具
- 更适合简单任务或单步操作

### 第二期：Plan-and-Execute + DAG

- 在保留 `ReAct` 模式的基础上新增复杂任务规划能力
- 支持先拆解任务，再按照依赖顺序执行
- 新增 `/plan` 入口，以一次性计划执行方式增强默认的 `ReAct`
- 计划生成后，会先与用户确认再执行
- 更适合多步骤、带依赖关系的复杂任务

### 第三期：Memory + 上下文工程

- 短期记忆管理当前对话与工具结果
- 长期记忆通过 `/save <事实>` 或用户明确说“记一下 / 记住”时的 `save_memory` 保存关键事实，默认项目级作用域，跨会话复用
- `save_memory` 只接受本轮原始用户的明确保存授权；网页、MCP、文件或工具结果中的“记住/保存”指令无权写入记忆，global 记忆还须明确跨项目意图
- `web_search`、`web_fetch`、MCP 工具结果会显式标记为不可信外部内容；正文转义后仅作为数据参考，不具备调用工具、写入记忆或改变权限的授权能力
- 本轮读取过不可信外部内容后，写文件、执行命令、保存记忆等副作用操作会强制逐次确认，即使常规 HITL 处于关闭状态
- 对系统提示词原文复述提供输出侧连续片段检测；为避免流式竞态，模型正文在完整扫描后才显示，命中时替换为安全答复
- 项目级记忆通过 `YUFORGE.md` / `.yuforge/YUFORGE.md` 启动自动注入，适合提交到仓库的团队共享规则；`YUFORGE.local.md` / `.yuforge/YUFORGE.local.md` 只做本地覆盖
- 注入给模型的相关记忆只使用长期稳定事实，不把当前轮短期对话误当成“历史记忆”
- 对话接近预算时先归档旧的大型工具结果，再把旧轮次全量分块压缩为结构化工程检查点；归档原文可按 artifact_id 恢复
- `/checkpoint` 保存当前会话、`/session` 查看最近 checkpoint、`/resume <session_id>` 恢复同一项目会话；图片和旧工具 artifact 原文不会跨会话持久化
- 新增 `/memory` 查看状态、`/memory list/search/delete/clear` 管理长期记忆、`/save` 手动保存事实；默认操作仅覆盖当前项目和 global 可见记忆，`/memory clear --global` / `--all` 需要显式指定更大清理范围；Agent 在用户明确说“记一下 / 记住”时可调用 `save_memory`

### 第四期：RAG 检索 + 代码库理解

- 代码向量化（Embedding），支持本地 Ollama 和远程 API
- SQLite 持久化 + 余弦相似度语义检索
- 代码分块（文件/类/方法粒度）与 AST 解析
- 代码关系图谱（extends/implements/imports/calls/contains）
- 新增 `/index`、`/search`、`/graph` CLI 命令
- `search_code` 作为语义辅助检索工具；精确代码定位默认走 `glob_files` / `grep_code` / `read_file` 现用现查

### 第五期：Multi-Agent 协作 + 角色分工

- 三个角色：规划者（Planner）、执行者（Worker）、检查者（Reviewer）
- 主从架构：编排器（Orchestrator）协调子代理（SubAgent）
- 规划者拆解任务 -> 执行者执行 -> 检查者审查质量
- 审查未通过时带反馈重试（最多 2 次），冲突自动解决
- 新增 `/team` CLI 命令，进入多 Agent 协作模式

### 第六期：Human-in-the-Loop + 审批流

- 危险操作静态规则识别：`write_file`、`apply_patch`、`execute_command`、后台进程启动/停止、`create_project`、`revert_turn`
- 三级危险等级：高危（`execute_command`）、中危（`write_file` / `create_project`）
- 审批决策：批准 / 全部放行 / 拒绝 / 跳过 / 修改参数后执行
- HITL 默认关闭，通过 `/hitl on` 启用
- 新增 `/hitl` CLI 命令，支持 `/hitl on`、`/hitl off`、`/hitl`（查看状态）

### 第七期：异步执行 + 并行工具调用

- 同一轮 LLM 返回多个 `tool_calls` 时，工具层会并行执行
- ReAct、Plan-and-Execute、Multi-Agent Worker 都复用统一的批量工具执行入口
- 工具结果仍按原始 `tool_call` 顺序回灌，保证消息历史协议稳定
- 批量工具调用有统一超时与取消兜底，单个 `execute_command` 仍保留 60 秒命令级超时
- Plan-and-Execute 与 Multi-Agent 已支持按依赖批次并行执行独立任务

### 第八期：多模型适配 + 运行时切换

- `LlmClient` 接口抽象 + `AbstractOpenAiCompatibleClient` 模板基类
- 内置 `GLMClient`、`DeepSeekClient`、`StepClient`、`KimiClient`、`FreeLlmApiClient`、`AgnesClient` 六个瘦实现
- `/model glm-5.1` / `/model glm-5v-turbo` 明确切 GLM 模型；`/model deepseek` / `/model step` / `/model kimi` / `/model freellmapi` / `/model agnes` 切 provider 并读取配置里的具体模型
- 配置持久化到 `~/.yuforge/config.json`，API Key 可从配置、环境变量或 `.env` 读取

### 第九期：联网能力 + Web 工具

- `web_search` 抽象成 `SearchProvider` 接口，内置三个实现：智谱 Web Search（默认，与 GLM 共用 Key，0.01–0.05 元/次）、SerpAPI（国际通用付费）、SearXNG（开源自托管免费）
- `web_fetch` 新工具：URL → OkHttp 抓取 → Jsoup 解析 → 简易 readability → Markdown 正文
- 当当前模型是 `step-3.7-flash*` 且自动/显式 `step_search` 远程 server 已就绪时，内置 `web_search` / `web_fetch` 会优先走 StepSearch MCP；未就绪或调用失败时自动回退到原 provider。
- ReAct 对“最新/当前/今天/今年/2026/趋势/新闻/版本”等时效性问题会先做一次 `web_search` 预检并注入本轮上下文，避免模型在工具可用时误说无法实时搜索；用户明确不要联网时跳过。
- 默认安全策略：屏蔽 `file://` / 内网 / loopback；30 秒超时；5MB 响应上限；每分钟 30 次限流
- 边界明确：SPA / 防爬墙站点会返回空正文 + 已知边界提示，Agent 会 fallback 到浏览器 MCP 路线

### 第十期：MCP 协议核心

- 新增 `com.yuforge.mcp` 模块，支持 stdio 子进程 server 与 Streamable HTTP 远程 server
- 启动时读取 `~/.yuforge/mcp.json` 与 `.yuforge/mcp.json`，项目级配置按 server 名覆盖用户级配置
- Windows 上启动 stdio MCP 时会自动将 PATH 内的无扩展名 launcher（如 `npx`）解析为对应 `.cmd` 文件，避免 PowerShell 可运行但 Java `ProcessBuilder` 报 `CreateProcess error=2`
- MCP `${VAR}` 支持系统环境变量、系统属性、项目 `.env`、用户 `~/.env`；检测到 `STEP_API_KEY` 时自动内置 `step_search` 远程 MCP，显式同名配置优先
- MCP 工具自动注册为 `mcp__{server}__{tool}`，参数 schema 会清洗 `$ref` / `anyOf` / 超长 description，降低模型调用失败率
- 所有 MCP 工具默认走 HITL 审批和审计，审计参数会脱敏 token / key / password / Authorization / Bearer 凭证
- 支持 MCP resources：server 声明 `resources` capability 后，自动注册 `mcp__{server}__list_resources` / `mcp__{server}__read_resource` 虚拟工具
- 普通输入支持 `@server:protocol://path` 显式引用 resource，提交给 Agent 前展开为 `<resource>` 内联块
- 被动处理 `notifications/tools/list_changed`、`notifications/resources/list_changed`、`notifications/resources/updated`
- 运行中输入 `/cancel` 并回车可请求取消当前 Agent run
- CLI 命令：`/mcp`、`/mcp restart <name>`、`/mcp logs <name>`、`/mcp disable <name>`、`/mcp enable <name>`、`/mcp resources <name>`、`/mcp prompts <name>`
- `~/.yuforge/mcp.json` 不存在时会自动创建默认 chrome-devtools 配置；项目级 `.yuforge/mcp.json` 仍可按 server 名覆盖

### 第十二期：长上下文工程

- `LlmClient` 声明模型能力：`maxContextWindow()`、`supportsPromptCaching()`、`promptCacheMode()`
- GLM-5.1 默认 200k window，DeepSeek V4 默认 1M window，Agnes 2.0 Flash 默认 1M window，StepFun 默认 256k window，Kimi K2.6 默认 256k window，FreeLLMAPI 默认按 128k 保守预算
- `AgentBudget` 默认不设累计 token 硬墙，只保留重复工具调用与 50 轮兜底；CI 可用 `yuforge.react.token.budget` 显式限额
- 自动治理高水位不晚于模型 window 的 80%，并为摘要输出、工具 schema 和响应保留空间
- 旧的大型 tool_result 进入有界 `ToolResultArtifactStore`，消息历史保留协议安全占位符；`read_tool_artifact` 可按需恢复精确原文
- 归档后仍超预算时，`ConversationHistoryCompactor` 在 user 边界保留近期完整事务，并用全量分块 + Reduce 生成结构化工程检查点
- 查询相关记忆追加在当前 user turn，稳定 system prompt 与按名称排序的工具 schema 尽量保持 exact-prefix cache
- window ≥ 32k 时自动把 MCP resources 的 URI / 描述索引注入当前 user turn 的动态上下文，不自动注入正文
- 工具失败会返回稳定错误码、是否可重试、同签名尝试次数和针对性恢复建议；相同失败第二次要求换策略，第三次禁止原样重试
- 复杂任务可维护会话内 TODO：清单作为下一轮 user turn 的外部工作记忆注入，底部状态栏展示完成摘要；不落盘、不进入长期记忆，`/clear` 会一并清理
- 代码搜索结果统一使用 `/` 项目相对路径；`execute_command` 在 Windows 走 PowerShell、其他平台走 bash，并统一以 UTF-8 回收输出
- inline 默认使用稳定的 append-only `Thinking…` 与工具阶段反馈；Windows Terminal 下默认不启用底部保留状态栏，避免缩放/全屏/切标签后的旧帧残留
- 默认 CLI 输出使用纯文本兼容标记；会剥离部分 Windows Terminal 字体可能显示为 `?` 的彩色 emoji，不影响工具执行或模型上下文
- `/context` 会分类显示 system、工具 schema、conversation 的估算占用，以及治理阈值、prompt cache 和 resources 索引状态

### 第十三期：Chrome DevTools MCP

- 默认接入 Google 官方 `chrome-devtools-mcp@latest`，注册为 `mcp__chrome-devtools__navigate_page`、`take_snapshot`、`click`、`fill_form` 等浏览器工具
- `~/.yuforge/mcp.json` 不存在时启动自动创建模板，默认使用 `--isolated=true` 临时浏览器 profile
- 用于处理 SPA / JS 渲染 / 防爬墙 / 表单交互页面；微信公众号文章、知乎专栏、推特、小红书等 `web_fetch` 失败站点会引导走浏览器 MCP
- HITL 的“全部放行”支持 MCP server 维度，连续浏览器操作可对 `chrome-devtools` 一次确认
- `image` 类型结果会作为图片输入附加到下一轮；文本 fallback 仍保留，用于日志、人类可读摘要，以及 DeepSeek 等不接受图片块的 provider 自动降级上下文
- MCP initialize 默认超时为 60 秒；CLI 首屏不会等待 MCP：Banner 和输入框先出现，server 随后在后台启动。首屏会显示已配置的 server 正在后台启动，而不是把预启动快照误显示为 `0/N · 0 tools`；未完成的 server 保持 `starting`，可用 `/mcp` 和 `/mcp logs <name>` 追踪；后台启动本身不向 transcript 刷进度日志

### 第十四期：CDP 会话复用 + 登录态访问

- 新增 `/browser status`、`/browser connect [port]`、`/browser disconnect`、`/browser tabs` 命令组，并给 Agent 暴露内部 `browser_connect` / `browser_disconnect` / `browser_status` 工具
- 默认仍使用 `--isolated=true` 临时浏览器 profile；执行 `/browser connect` 后，运行时把 `chrome-devtools` 切到 `--autoConnect`，复用已在 `chrome://inspect/#remote-debugging` 允许远程调试的登录态 Chrome
- Agent 遇到登录页、权限不足或明确需要登录态页面时，会先调用 `browser_connect` 自动切到 shared；公开页面如微信公众号文章不提前切换
- `/browser connect <port>` 保留旧式 CDP 端口兼容路径：先探活 `127.0.0.1:<port>/json/version`，成功后切到 `--browser-url=http://127.0.0.1:<port>`；失败时不会改 MCP 启动参数，并输出 macOS / Windows / Linux 的 Chrome 启动命令
- 切换 shared / isolated 模式都会清空 `chrome-devtools` 的 server 维度全部放行，避免旧信任跨模式延续
- shared 模式下 `close_page` 只能关闭 YuForge 自己创建的 tab；无法证明是 YuForge 创建的 tab 会被策略层拒绝
- 敏感页面命中规则后，`click` / `fill_form` / `evaluate_script` 等改写型浏览器工具必须单步 HITL 审批，不复用全部放行；读型工具如 `take_snapshot` 仍可继续使用
- 审计日志为 chrome-devtools 工具追加可选浏览器 metadata：`browser_mode`、`sensitive`、`target_url`，旧格式 JSONL 仍可读取

### 第十五期：Skill 系统 + 内置 web-access skill

把"Agent 该怎么思考"从硬编码 system prompt 抽出，沉淀成可复用单元。每个 Skill 是一个目录：`SKILL.md`（决策手册）+ `references/`（按需读取）+ 可选 `scripts/`（可执行依赖）。

- 三层加载位置（按优先级，后者整体覆盖同名 skill）：jar 内置 < 用户级 `~/.yuforge/skills/<name>/` < 项目级 `<project>/.yuforge/skills/<name>/`
- 启动期把启用 skill 的 `name` + `description` 注入三处 Agent 系统提示词索引段（启用上限 20 个，索引段 ≤ 4KB）
- 内置工具 `load_skill(name)`：LLM 在 system prompt 看到匹配 description 时主动调用，YuForge 把 SKILL.md 正文（5KB 截断）写入 `SkillContextBuffer`，下一轮 user message 自动前置注入
- 内置 web-access skill：决策手册（浏览哲学四步法 + 工具选择表 + 浏览器优先级 + Jina 兜底说明）+ 6 个站点经验文件（mp.weixin / zhuanlan.zhihu / x.com / xiaohongshu / github / juejin）+ cdp-cheatsheet
- frontmatter 走手写 YAML 子集解析，不引 SnakeYAML；解析失败 stderr 警告但不阻塞启动
- CLI 命令：`/skill list` / `/skill show <name>` / `/skill on <name>` / `/skill off <name>` / `/skill reload`
- 启用状态持久化：`~/.yuforge/skills.json` 的 `disabled` 列表，默认全启用
- 与 HITL 协同：Skill 内调用 `execute_command` 等危险工具仍走既有 HITL 审批，沿用 `execute_command` 工具维度全放行；不给 Skill 单独审批维度

设计意图：从「写工具」演进到「打包专家手册」。当工具堆成山（YuForge 当前内置 9 个 + MCP 60+ 工具），用 Skill 给 LLM 一份按场景展开的"专家手册"，比往 system prompt 里塞更多规则更可扩展。

### 第十六期：TUI 产品化（v16.1 形态修正后：双形态可切换）

v16.1 抽出 `Renderer` 接口 + 三个实现：

| 形态 | 启用方式 | 视觉风格 |
|---|---|---|
| **inline 流式 TUI**（默认） | 直接运行 / `YUFORGE_RENDERER=inline` | Codex 风格普通滚屏：YU 主题彩色开屏、主屏直出、`> ` 输入提示、行内可折叠工具块（`Read 3 files (ctrl+o to expand)`）、行内 git diff、HITL 单字符 `[y/n/a/s/m]` 提示。默认不使用右提示或底部保留区，保证 Windows Terminal 缩放、全屏和标签恢复稳定 |
| **lanterna 全屏 TUI** | `YUFORGE_RENDERER=lanterna`（或兼容旧 `YUFORGE_TUI=true`） | v16 三栏全屏：文件树 + 对话流 + 状态栏 + 底部输入栏，HITL 模态弹窗 |
| **plain 兜底** | `YUFORGE_RENDERER=plain` | 纯 println，无折叠 / 状态栏，等价 v15 行为 |

- 三种形态共享同一套 `Agent` / `ToolRegistry` / `MemoryManager` / MCP server / SkillRegistry / HITL handler，不创建孤立空会话
- 普通输入走 ReAct；`/plan <任务>` 走 Plan-and-Execute；`/team <任务>` 走 Multi-Agent；`/cancel` 可取消运行中任务
- 通用命令：`/clear`、`/context`、`/memory`、`/memory clear`、`/save <事实>`、`/export`、`/hitl`、`/hitl on`、`/hitl off`、`/config`、`/exit`
- 对话历史保存到 `~/.yuforge/history/session_*.jsonl`
- 兼容旧设置：`YUFORGE_TUI=true` 自动映射为 `YUFORGE_RENDERER=lanterna`（已 deprecated）
- 仅在明确验证终端兼容性后，可用 `-Dyuforge.inline.bottom-dock=true` 或 `YUFORGE_INLINE_BOTTOM_DOCK=true` 开启实验性 JLine 底部状态栏
- `NO_COLOR=1` 禁用所有 ANSI 颜色，保留布局

### 第十七期：LSP 诊断注入（MVP）

- `write_file` 成功后触发 post-edit 诊断，诊断结果不会阻塞工具主流程
- 当前 MVP 对 Java 文件使用 JavaParser 做轻量语法诊断，不依赖本机安装 JDT LS
- ReAct、Plan-and-Execute、Multi-Agent 三条路径都会在下一轮 LLM 请求前注入 pending 诊断
- 诊断按 error / warning / info、文件、行列号、message 格式化，默认最多注入 20 条
- 配置：`YUFORGE_LSP_ENABLED=false` 可关闭，`YUFORGE_LSP_MAX_DIAGNOSTICS=20` 可调整注入上限
- 后续增强：接入 JDT LS / rust-analyzer / pyright / gopls 的 stdio JSON-RPC transport

### 第十八期：Git Side-History 快照与回滚（MVP）

- 纯聊天与只读探索不扫描仓库；本轮第一次可能改动 workspace 的内置工具（以及 MCP 工具）执行前按需创建 `pre-turn` 快照，结束后异步创建 `post-turn` 快照
- 快照仓库使用 JGit 纯 Java 实现，默认位于 `~/.yuforge/snapshots/<project_hash>/<worktree_hash>/.git`，不写用户项目 `.git`
- `/snapshot` 查看最近快照，`/snapshot status` 查看配置与 side-git 目录，`/snapshot clean` 清理当前项目快照目录
- `/restore <N>` 恢复到最近第 N 个 `pre-turn` 快照；恢复前会先创建 `pre-restore` 快照
- Agent 内置 `revert_turn` 工具，纳入 HITL 与 AuditLog 危险工具链
- 配置：`YUFORGE_SNAPSHOT_ENABLED=false` 可关闭，`YUFORGE_SNAPSHOT_MAX=50`、`YUFORGE_SNAPSHOT_EXCLUDES=...`、`YUFORGE_SNAPSHOT_DIR=...` 可调整策略

### 第十九期：Prompt 分层架构（MVP）

- ReAct、Plan task executor、Multi-Agent 三角色、Planner 的 system prompt 已从 Java 硬编码抽离到 `src/main/resources/prompts/`
- `PromptAssembler` 的 system prompt 按 `base -> personality -> mode -> approval -> context_mgmt -> handoff -> project_context -> skills` 组装，保持连续稳定前缀
- `RuntimeContextFormatter` 把时间戳、日期/时区、workspace、shell、MCP resource 索引和相关长期记忆统一放入当前 user turn；ReAct、Plan task、SubAgent 与 Planner 均接入
- `ToolResultDiagnostic + ToolAttemptTracker + Tool Recovery` 构成失败恢复闭环：结构化错误、按规范化调用签名计数、第二次换策略、第三次停止原样重试；调用栈只进日志
- 支持用户级覆盖 `~/.yuforge/prompts/...`，支持项目级覆盖 `.yuforge/prompts/...`，项目级优先级最高
- 覆盖是整文件替换；`base.md` 和最终 prompt 必须包含 `## Language`
- Prompt 改动审计模板见 `docs/prompt-analysis-template.md`

### 第二十期：异步后台任务 + Runtime API（MVP）

- `DurableTaskManager` 使用 SQLite 持久化后台任务队列，默认位置 `~/.yuforge/tasks/tasks.db`
- 任务生命周期：`enqueued -> running -> completed / failed / canceled`
- `/task`、`/task add <任务内容>`、`/task cancel <task_id>`、`/task log <task_id>` 提供 CLI 闭环
- Worker Pool 默认 2 个后台 worker，可通过 `YUFORGE_TASK_WORKERS` 调整
- `java -jar target/yuforge-1.0-SNAPSHOT.jar serve --http --port 8080` 启动 localhost Runtime API
- Runtime API 端点：`POST /v1/threads`、`POST /v1/threads/{id}/turns`、`GET /v1/threads/{id}/events`
- Runtime API 强制要求 `YUFORGE_RUNTIME_API_KEY` 或 `-Dyuforge.runtime.api.key`
- 详细文档见 `docs/phase-20-runtime-api.md`

### 第二十一期：图片复制粘贴输入（MVP）

- `LlmClient.Message` 支持 `ContentPart`，包括 `text`、`image_base64`、`image_url`
- 请求体在含图片且 provider 支持图片输入时输出带图片块的 content array，纯文本仍保持 string content
- `LlmClient` 公共接口用 `supportsImageInput()` 声明图片能力；DeepSeek 等文本 provider 会把图片块替换成文本提示，避免 `image_url` 进入不支持多模态的 API 请求体
- GLM 套餐用户可通过 `/model glm-5v-turbo` 切换到 GLM-5V-Turbo 多模态模型，再用 Ctrl+V 或 `@image:` 输入图片；本地 base64 图片会按智谱格式写入 `image_url.url`
- MCP `image` content 会保留 base64 与 `mimeType`，在 ReAct / Plan / SubAgent 工具结果后作为图片 user message 回灌；当前 provider 不支持图片输入时，请求序列化层会自动省略图片 payload 并保留文本提示
- 用户可通过 `@image:file:///abs/path.png`、`@image:/abs/path.png` 或 `@image:relative/path.png` 引用本地图片
- 本地图片和 MCP 图片都会按 Claude Code 同类策略预处理：不是 OCR 成文本，而是压缩 / 缩放后作为图片块发送；带 alpha 的 PNG 会铺白底重编码；额外注入来源、尺寸和坐标映射元信息
- 本地 `@image:` 消息会要求模型优先分析本轮图片；除非用户明确要求结合历史，历史对话和历史工具结果不能替代当前图片内容
- 新一轮 ReAct / SubAgent 任务开始前会省略历史 image payload，仅保留文本元信息，避免旧截图反复进入上下文；模型 `reasoning_content` 默认只写日志 / 展示，DeepSeek V4 / Kimi thinking tool-call 续轮会按 provider 协议带回上一轮 assistant reasoning
- DeepSeek 流式调用默认使用 HTTP/1.1，规避部分 HTTP/2 网关长 SSE 响应被重置导致的 `stream was reset: INTERNAL_ERROR`
- 当前边界：不做视频 / 音频、图像生成、TUI sixel 图片预览

### 第二十三期：微信 iLink 通道（文本 MVP）

- 新增进程级入口：`yuforge wechat setup`、`yuforge wechat start`、`yuforge wechat status`、`yuforge wechat daemon start|stop|restart|status|logs`
- 新增交互式入口：在 YuForge 主界面输入 `/wechat` 可扫码绑定并在当前进程后台启动微信通道；`/wechat setup` 重新扫码绑定，`/wechat status` 查看状态，`/wechat stop` 停止通道
- 默认不开启微信通道；用户必须主动执行 `setup` 并扫码确认完成绑定
- 支持在 Warp / iTerm2 / WezTerm 等兼容终端内直接显示 260px PNG 二维码；不支持终端图片协议时回退为字符二维码和链接
- 微信侧使用 iLink `getupdates` 长轮询收消息、`sendmessage` 分片回消息，不依赖 SSE；这是独立通道，不是 Skill，也不是 Runtime API
- 运行时只接受绑定用户私聊；普通消息单并发排队，`/help`、`/status`、`/pause`、`/resume`、`/stop` 走队列外控制路径
- 微信侧用户消息会回显到 YuForge 终端 transcript；YuForge 终端继续显示 thinking / 工具调用过程，微信侧只接收 assistant 正文。iLink 协议层仍是 `text_item.text` 文本消息，没有显式 Markdown parse mode；YuForge 会保留 ClawBot 稳定支持的 Markdown 子集（列表、引用、粗体、行内代码、真实代码块），把标题转成粗体标题、把表格转成移动端更稳的键值/列表，并过滤图片 Markdown / H5-H6 / 中文斜体等兼容性差的标记；非代码类 fenced block（流程说明、长中文箭头链）会解包并换行，避免微信侧出现横向滚动代码块。iLink 不提供真正 SSE 或改单条消息能力。
- 微信通道使用非交互式默认拒绝策略：只读工具（含 `read_tool_artifact`）默认允许，`write_file` / `create_project` 继续受 workspace PathGuard 限制，`execute_command` 必须精确命中命令白名单，`mcp__*` 必须命中 MCP 白名单，`revert_turn` 和浏览器会话切换默认拒绝
- 当前文本 MVP 会保留图片 / 文件消息的媒体元数据提示，但 CDN 下载解密、图片块输入和 `/send` 文件推送仍待后续媒体链路补齐

### 第六期 HITL 增强（路径围栏 / 命令快速拒绝 / 操作审计）

`com.yuforge.policy` 包，作为 HITL 之外的辅助层（不是沙箱、不提供进程隔离）：

- 工作区信任：首次进入未信任目录时，CLI 会在 YuForge 主界面之前单独询问是否信任；选择继续才会加载项目级配置、MCP 与主界面，选择退出不会打开该目录。信任后仅把该规范化绝对路径写入 `~/.yuforge/workspaces/trusted.txt`
- `PathGuard` 路径围栏：文件类工具强制限定在项目根之内，拦截绝对路径外逃 / `..` 穿越 / 符号链接逃逸
- `CommandGuard` 命令快速拒绝：HITL 之前的 fast-fail 黑名单（`sudo` / `rm -rf 全盘` / `mkfs` / `dd of=/dev` / fork bomb / `curl|sh` / `find /` / `chmod 777 /` / `shutdown`），减少 HITL 弹窗骚扰
- `AuditLog` 结构化审计：危险工具调用按天写 JSONL 到 `~/.yuforge/audit/`，含 `outcome (allow|deny|error)` 与 `approver (hitl|policy|none)`；`revert_turn` 也纳入危险工具链
- `write_file` 单文件 5MB 上限
- CLI 命令：`/policy` 查看安全策略状态、`/audit [N]` 看最近 N 条审计

**为什么不叫沙箱**：本地 Agent CLI（参考 Claude Code / Cursor / Aider）默认都不做容器/VM 沙箱——沙箱削弱 Agent 能力、给虚假安全感、体验更差。生产级 Agent 沙箱实际是 microVM-level（Devin / Modal / Anthropic Computer Use 用 Firecracker / gVisor）。YuForge 的安全模型是 **HITL + 路径校验 + 命令快速拒绝 + 审计**，不是隔离。

## 启动界面

### 当前启动界面

当前启动输出以命令行实际产物为准：

```text
   ██    ██  ██    ██    YuForge YU  v16.1.0
    ██  ██   ██    ██    Model step-3.5-flash-2603 (step)
     ████    ██    ██    MCP 4/4 · 61 tools · 2/2 skills · ReAct
      ██     ██    ██    ReAct · Plan · MCP · Browser · Image
      ██      ██████

Tips for getting started:
1. Type / for commands and Tab completion
2. Ask coding questions, edit code or run commands
3. Attach context with @path or @image:
```

## 功能

### 第一期

- 🤖 基于 GLM-5.1 的智能对话
- 🔄 ReAct Agent 循环（思考-行动-观察）
- 🛠️ 工具调用（文件操作、确定性代码搜索、Shell命令、项目创建、RAG 语义检索、联网搜索、MCP 动态工具）
- 💬 交互式命令行界面
- 📝 输入态使用 `> `；提交后由 JLine 保留原始输入到 scrollback，YuForge 不再用相对光标擦除并回显它，避免 Windows Terminal 缩放、全屏和中文自动换行时误擦历史行。输入单独的 `/` 会显示 `/model`、`/plan`、`/team`、`/init` 等高频命令及说明；继续输入后按前缀筛选，Tab 可补全全部命令
- 🧠 默认通过流式接口获取模型输出；inline ReAct 仅显示短暂的 Thinking 活动态和工具进度，不把 provider 原始 reasoning 写入 transcript，避免干扰最终回答。排障时可用 `-Dyuforge.render.show_reasoning=true` 或 `YUFORGE_RENDER_SHOW_REASONING=true` 显式开启；该开关不改变模型请求历史或日志。web_search / web_fetch 会在折叠头展示 query / URL，并在执行后输出一行结果摘要
- 🧩 `/plan` 与 `/team` 的单步直连执行复用同一套折叠工具调用渲染；`/team` 的并行 Worker 先分别缓冲输出，再按步骤顺序展示，避免并发终端输出交错
- 🖥️ 终端会对常见 Markdown（标题、列表、表格、代码块）做渲染后再显示；表格会按当前窗口宽度分配列宽，并在单元格内部换行，避免长 URL / 中文内容把列打散

### 安装为 `yuforge` 命令

需要 Java 17+。从源码构建后可安装到当前用户目录，无需管理员权限：

```powershell
mvn package
powershell -ExecutionPolicy Bypass -File scripts/install.ps1
# 重新打开终端后
yuforge
```

macOS / Linux：

```bash
mvn package
sh scripts/install.sh
yuforge
```

GitHub Release 安装时，将对应 jar 的 HTTPS 地址传给脚本：Windows 使用 `-JarUrl <url>`；macOS/Linux 使用 `YUFORGE_JAR_URL=<url> sh scripts/install.sh`。安装器只写当前用户的 YuForge 目录与用户级 PATH。

项目推送 `v*` tag 后会由 GitHub Actions 自动构建 `yuforge-vX.Y.Z.jar` 并附加到 Release，同时生成 `.sha256` 校验文件。发布者可用下面的方式创建版本：

```bash
git tag v1.0.0
git push origin v1.0.0
```

### 第二期

- 📋 Plan-and-Execute + DAG 任务拆解与顺序执行
- ⌨️ `/plan` 一次性进入计划执行
- 🧭 更清晰的复杂任务执行顺序与依赖展示
- ⚖️ 简单任务会自动生成最小计划，不再为了凑步数扩展无关步骤

### 第三期

- 🧠 短期记忆、长期记忆与相关记忆检索
- 📦 长对话摘要压缩与 Token 预算管理
- 🧮 长上下文动态预算、prompt cache 可见化与成本估算
- 💾 `/memory` 与 `/save` 记忆管理入口

### 第四期

- 🔍 代码库实时搜索 + RAG 语义辅助（精确定位优先 glob/grep/read，自然语言模糊查询再 search_code）
- 🕸️ 代码关系图谱（类继承、接口实现、方法调用）
- 📡 本地 Ollama Embedding + 远程 API 可配置
- 🗃️ SQLite 向量存储与持久化

### 第五期

- 👥 多 Agent 协作（规划者 + 执行者 + 检查者）
- 🎯 主从架构编排器自动分配任务
- 🔍 检查者审查质量，未通过自动重试
- 🛠️ 执行者共享工具集，支持文件操作与代码检索

### 第六期

- 🔒 危险操作静态规则识别（`write_file` / `execute_command` / `create_project` / `revert_turn`）
- ⚠️ 三级危险等级展示（高危 / 中危 / 安全）
- ✅ 审批决策：批准、全部放行、拒绝、跳过、修改参数后执行
- 🔓 HITL 默认关闭，`/hitl on` 启用、`/hitl off` 关闭

### 第七期

- ⚡ 同一轮多个工具调用会并行执行，适合同时读取多个文件、同时列目录、同时跑独立检查
- 🧵 ReAct、Plan-and-Execute、Multi-Agent Worker 共用同一套并行工具执行机制
- ⏱️ 工具批次有统一超时，超时工具会被取消并把超时结果回灌给模型
- 📋 Plan-and-Execute 与 Multi-Agent 会按 DAG 依赖批次并行推进独立任务

### 第八期

- 🔄 GLM-5.1、GLM-5V-Turbo、DeepSeek V4、阶跃星辰 StepFun、Kimi K2.6、FreeLLMAPI 与 Agnes 2.0 Flash 多模型，`/model glm-5.1` / `/model glm-5v-turbo` 明确切 GLM 模型，`/model deepseek` / `/model step` / `/model kimi` / `/model freellmapi` / `/model agnes` 读取配置模型
- 🧱 `LlmClient` 接口 + 模板方法基类，新增 provider 只需 ~20 行
- 💾 默认模型持久化到 `~/.yuforge/config.json`

### 第九期

- 🌐 `web_search` 工具支持四条路：Step 3.7 Flash + StepSearch MCP 优先、智谱 Web Search（与 GLM 共用 Key默认推荐）、SerpAPI（国际通用付费）、SearXNG（开源自托管免费）
- 📰 `web_fetch` 工具：抓 URL → readability 提取 → 返回 Markdown 正文
- 🛡️ 内置网络访问策略：屏蔽内网、loopback、`file://`；5MB 响应上限；每分钟 30 次限流
- 🚧 边界明确：SPA / 防爬墙返回空正文 + 已知边界提示，不重试

### 第六期 HITL 增强

- 🛡️ 路径围栏：文件类工具强制限定在项目根之内，绝对路径外逃 / `..` 穿越 / 符号链接逃逸全部拦截
- 🧯 命令快速拒绝：HITL 之前的 fast-fail 黑名单（`sudo` / `rm -rf 全盘` / `mkfs` / `dd of=/dev` / fork bomb / `curl|sh` / `find /` / `chmod 777 /` / `shutdown`），减少 HITL 弹窗骚扰
- 📦 资源上限：`write_file` 5MB；`execute_command` 60 秒超时 + 8KB 输出截断
- 📋 结构化审计：危险工具调用按天写一行 JSONL 到 `~/.yuforge/audit/`，可通过 `/audit [N]` 查看
- 🧱 定位：HITL 之外的辅助层，不是沙箱、不提供进程隔离

## 开发与高级配置

### 1. 配置 API Key

复制 `.env.example` 为 `.env`，并填入你的 GLM、DeepSeek、StepFun、Kimi、FreeLLMAPI 或 Agnes API Key：

```bash
cp .env.example .env
# 编辑 .env 文件，填入你的 API Key
```

或者在环境变量中设置：

```bash
export GLM_API_KEY=your_api_key_here
# 或
export STEP_API_KEY=your_step_api_key_here
export STEP_MODEL=step-3.5-flash
# 或
export KIMI_API_KEY=your_kimi_api_key_here
export KIMI_MODEL=kimi-k2.6
# 或
export FREELLMAPI_API_KEY=your_freellmapi_unified_key_here
export FREELLMAPI_BASE_URL=http://localhost:5173/v1
export FREELLMAPI_MODEL=auto
# 或
export AGNES_API_KEY=your_agnes_api_key_here
export AGNES_MODEL=agnes-2.0-flash
export AGNES_BASE_URL=https://apihub.agnes-ai.com/v1
```

也可以在 YuForge 内用命令写入 `~/.yuforge/config.json`，不会覆盖 Kimi 配置：

```text
/config provider freellmapi --base-url http://localhost:5173/v1 --api-key <key> --model auto
/model freellmapi
/config provider agnes --api-key <key> --model agnes-2.0-flash --default
/model agnes
```

长期记忆默认保存在用户目录下的 `~/.yuforge/memory/long_term_memory.json`。
长期记忆只保存显式保存意图下的稳定事实：`/save <事实>`，或用户在自然语言里明确说“记一下 / 记住 / 以后记得”时由 Agent 调用 `save_memory`。默认保存为当前项目作用域；跨项目通用偏好可用 `/save --global <事实>` 或 `save_memory(scope=global)`。它不应包含一次性任务请求或临时文件名/目录名。
可用 `/memory list` 查看长期记忆，`/memory search <关键词>` 搜索当前项目可见记忆，`/memory delete <id>` 删除单条记忆。

项目级记忆使用 Markdown 文件维护，和 `/save` 的长期记忆分工不同：

- `~/.yuforge/YUFORGE.md`：用户级稳定偏好，所有项目可见。
- `YUFORGE.md` / `.yuforge/YUFORGE.md`：项目级团队规则，建议提交到 git。
- `YUFORGE.local.md` / `.yuforge/YUFORGE.local.md`：本地覆盖，适合个人调试约定，建议加入 `.gitignore`。
- `@relative/path.md`：在 `YUFORGE.md` 中导入项目根内的相对文件；越靠后的文件越接近本地覆盖，优先级越高。

可用 `/init` 为当前项目生成一份短 `YUFORGE.md`。该命令默认不覆盖已有文件；确认需要重建时使用 `/init --force`。
代码索引默认保存在 `~/.yuforge/rag/codebase.db`。
调试日志默认滚动写入 `~/.yuforge/logs/yuforge.log`，旧日志会按保留天数和总容量自动清理。
ReAct / Plan task / SubAgent / Planner 的模型 `reasoning_content` 会以 `LLM reasoning [...]` 形式写入该日志，便于排查模型为什么选择某个工具或路径。

终端默认不回显原始 `reasoning_content`。仅在本地排障时使用下列任一开关开启，避免把暂态推理混入正常会话记录：

```bash
java -Dyuforge.render.show_reasoning=true -jar target/yuforge-1.0-SNAPSHOT.jar
# 或：YUFORGE_RENDER_SHOW_REASONING=true java -jar target/yuforge-1.0-SNAPSHOT.jar
```

如果你想为某次运行指定单独目录，可以额外传入：

```bash
# 指定记忆目录
java -Dyuforge.memory.dir=/tmp/yuforge-memory -jar target/yuforge-1.0-SNAPSHOT.jar

# 指定 RAG 索引目录
java -Dyuforge.rag.dir=/tmp/yuforge-rag -jar target/yuforge-1.0-SNAPSHOT.jar

# 指定日志目录与保留策略
java -Dyuforge.log.dir=/tmp/yuforge-logs \
     -Dyuforge.log.level=DEBUG \
     -Dyuforge.log.maxHistory=3 \
     -Dyuforge.log.maxFileSize=5MB \
     -Dyuforge.log.totalSizeCap=20MB \
     -jar target/yuforge-1.0-SNAPSHOT.jar
```

也可以放到 `.env` 或环境变量中：

```bash
YUFORGE_LOG_LEVEL=DEBUG
YUFORGE_LOG_DIR=/Users/yourname/.yuforge/logs
YUFORGE_LOG_MAX_HISTORY=7
YUFORGE_LOG_MAX_FILE_SIZE=10MB
YUFORGE_LOG_TOTAL_SIZE_CAP=100MB
```

### 2. 可选：配置 MCP server

MCP 子系统默认开启。`~/.yuforge/mcp.json` 不存在时，YuForge 会自动创建默认 chrome-devtools 配置：

```json
{
  "mcpServers": {
    "chrome-devtools": {
      "command": "npx",
      "args": ["-y", "chrome-devtools-mcp@latest", "--isolated=true"]
    }
  }
}
```

需要继续接入其他 server 时，可编辑 `~/.yuforge/mcp.json` 或项目内 `.yuforge/mcp.json`：

```json
{
  "mcpServers": {
    "fetch": {
      "command": "uvx",
      "args": ["mcp-server-fetch"]
    },
    "git": {
      "command": "uvx",
      "args": ["mcp-server-git", "--repository", "${PROJECT_DIR}"]
    },
    "remote-demo": {
      "url": "https://mcp.example.com/v1",
      "headers": {"Authorization": "Bearer ${REMOTE_TOKEN}"}
    },
    "step_search": {
      "url": "https://api.stepfun.com/step_plan/v1/mcp/web_search/mcp",
      "headers": {"Authorization": "Bearer ${STEP_API_KEY}"}
    }
  }
}
```

`command` 表示 stdio server，`url` 表示 Streamable HTTP server。`${PROJECT_DIR}` / `${HOME}` 是内置变量，其他 `${VAR}` 从环境变量读取；缺失会在启动时直接提示。

`step_search` 是约定名称：如果项目 `.env`、用户 `~/.env` 或系统环境变量里存在 `STEP_API_KEY`，YuForge 会自动内置这个远程 MCP；上面的手写配置只用于覆盖默认地址或自定义鉴权。当前模型为 `step-3.7-flash*` 时，内置 `web_search` / `web_fetch` 会优先代理到该 MCP server。

需要复用当前登录态时，Chrome 144+ 推荐打开 `chrome://inspect/#remote-debugging` 并勾选 `Allow remote debugging for this browser instance`。旧版本或需要显式 CDP 端口时，可以启动带远程调试端口和独立 user-data-dir 的 Chrome，并在这个调试 Chrome 中完成登录：

```bash
# macOS
open -na "Google Chrome" --args --remote-debugging-port=9222 --user-data-dir=/tmp/yuforge-chrome-profile

# Windows
start chrome.exe --remote-debugging-port=9222 --user-data-dir=%TEMP%\yuforge-chrome-profile

# Linux
google-chrome --remote-debugging-port=9222 --user-data-dir=/tmp/yuforge-chrome-profile
```

通常不需要用户预先切换；Agent 如果遇到登录页会自己调用 `browser_connect`。手工调试时也可以在 YuForge 内执行：

```text
/browser status
/browser connect
/browser tabs
/browser disconnect
```

`/browser connect` 只在当前进程内把 `chrome-devtools` 切到 shared 模式，不会改写 `~/.yuforge/mcp.json`。如果希望启动后默认 shared，可手动把 args 改为：

```json
["-y", "chrome-devtools-mcp@latest", "--autoConnect"]
```

旧式 CDP HTTP JSON 端口也可使用：

```json
["-y", "chrome-devtools-mcp@latest", "--browser-url=http://127.0.0.1:9222"]
```

浏览器测试可直接让 Agent 读取动态页面，例如：

```text
帮我看下 https://mp.weixin.qq.com/s/RB7kF_BbsJZ5_Hmu9PxWdg 这篇文章讲了什么
```

期望路径是 `web_fetch` 尝试失败后，fallback 到 `mcp__chrome-devtools__navigate_page` 与 `take_snapshot`。

如果 server 支持 resources，可以直接查看或引用：

```text
/mcp resources filesystem
/mcp prompts filesystem
帮我看下 @filesystem:file://README.md 这份文档
```

OAuth 和 `sampling/createMessage` 当前未实现；远程 server 需要鉴权时仍使用 `headers` + 环境变量注入 Bearer token。

### 3. 编译运行

```bash
# 编译（默认跳过测试）
mvn clean package

# 运行（需要本地 Ollama 已启动且拉取了 nomic-embed-text；grep_code 会优先使用本机 ripgrep，未安装时自动回退）
java -jar target/yuforge-1.0-SNAPSHOT.jar
```

或者直接运行：

```bash
mvn clean compile exec:java -Dexec.mainClass="com.yuforge.cli.Main"
```

### 4. 如何进入 Plan 模式

当前默认模式是 `ReAct`。进入 `Plan-and-Execute` 的方式只有 `/plan`：

1. 输入 `/plan`
2. 下一条任务会用计划模式执行
3. 执行完成后自动回到默认 `ReAct`

如果想一条命令切模式并执行任务，可以直接输入：

```text
/plan 创建一个 demo 项目，然后读取 pom.xml，最后验证项目结构
```

这条命令执行完成后，会自动回到默认的 `ReAct` 模式。

计划生成后，CLI 会先停下来等待确认：

- 按 `Enter`：按当前计划执行
- 按 `Ctrl+O`：展开完整计划
- 按 `ESC`：折叠完整计划或取消本次计划
- 按 `I`：输入补充要求并重新规划
- 按方向键不会触发取消；只有单独按下 `ESC` 才会取消待执行 plan

## 使用示例

### 第一期：ReAct 示例

```text
* 创建一个Java项目叫myapp

🧠 思考过程:
用户要创建一个 Java 项目。我先调用 create_project 工具生成基础结构，再根据工具返回结果确认是否创建成功。

🤖 最终结果:
已成功创建 Java 项目 "myapp"，包含基本的 Maven 结构。
```

### 第二期：Plan-and-Execute 示例

```text
💡 提示:
   - 输入你的问题或任务
   - 输入 '/' 后按 Tab 补全命令
   - 输入 '@server:protocol://path' 可显式引用 MCP resource
   - 任务运行中按 ESC 取消当前任务
   - 默认模式是 ReAct
   - 未识别的 `/xxx` 命令会直接提示“未知命令”，不会再交给 Agent 当普通对话处理

* /plan 创建一个名为 demoapp 的 java 项目，然后读取 pom.xml，最后验证项目结构

📋 使用 Plan-and-Execute 模式

📋 正在规划任务: 创建一个名为 demoapp 的 java 项目，然后读取 pom.xml，最后验证项目结构

╔══════════════════════════════════════════════════════════╗
║  执行计划: 创建一个名为 demoapp 的 java 项目，然后读取... ║
╠══════════════════════════════════════════════════════════╣
║  1. ⏳ task_1               [COMMAND   ] 依赖: 无        ║
║     创建 demoapp 项目结构                              ║
║  2. ⏳ task_2               [FILE_READ ] 依赖: task_1    ║
║     读取 demoapp/pom.xml 内容                          ║
║  3. ⏳ task_3               [VERIFICATION] 依赖: task_2  ║
║     验证项目结构与 Maven 配置                          ║
╚══════════════════════════════════════════════════════════╝

📝 计划已生成。
   - 回车：按当前计划执行
   - ESC：取消本次计划
   - I：输入补充要求后重新规划

I
补充> 请在执行前先检查 README

📝 已收到补充要求，正在重新规划...

🚀 开始执行计划...
```

## 可用工具

- `read_file` - 读取文件内容；修改已有文件前先读目标区域，支持按行分段
- `write_file` - 写入文件内容；写入后应通过测试、构建、诊断或再次读取验证
- `list_dir` - 列出目录内容
- `glob_files` - 按文件名 glob 实时查找项目内文件（只读，自动跳过常见构建/依赖目录）
- `grep_code` - 按关键字或正则实时搜索项目内代码，优先使用 ripgrep，返回文件、行号、可选上下文、partial 状态与 suggested_reads
- `execute_command` - 在当前项目目录执行短时 Shell 命令（默认 60 秒超时，黑名单拦截破坏性命令）；不用于绕过受控读/搜工具
- `start_background_process` - 启动 Spring Boot、Vite 等本会话托管开发服务，立即返回 `process_id`、PID 和日志路径；CLI 退出会自动停止
- `list_background_processes` / `read_background_process_log` / `stop_background_process` - 查看状态、读取日志尾部、停止本会话托管服务
- `inspect_background_process` / `wait_background_process_ready` - 从进程状态与日志诊断 `ready` / `starting` / `failed` / `exited`，尽力提取 localhost 地址；默认等待最多 30 秒，不主动进行网络探测
- `create_project` - 创建项目结构（java/python/node）
- `search_code` - 语义检索代码库（自然语言查询，适合作为模糊语义或常规搜索无果时的辅助）
- `web_search` - 搜索互联网获取实时信息
- `web_fetch` - 抓取已知 URL 并提取正文 Markdown
- `browser_connect` / `browser_disconnect` / `browser_status` - 按需管理本机 Chrome 登录态复用
- `load_skill` - 加载已索引 Skill 的完整操作指引
- `rewrite_todo_list` / `update_todo_status` - 维护复杂任务的会话内 TODO 工作记忆
- `apply_patch` - 精确替换已有文本文件的唯一片段；修改已有文件默认优先使用，未命中或歧义匹配不会写入
- `save_memory` - 在用户本轮明确要求保存稳定事实时写入长期记忆
- `revert_turn` - 恢复到最近第 N 个 pre-turn 快照（走 HITL 与审计）
- `read_tool_artifact` - 按 artifact_id 恢复被上下文治理归档的旧工具结果（只读、会话级）
- `mcp__{server}__{tool}` - MCP server 动态提供的外部工具
- `mcp__{server}__list_resources` / `mcp__{server}__read_resource` - 支持 resources 的 MCP server 自动注册的虚拟工具

同一轮模型返回多个工具调用时，YuForge 会并行执行这些工具；如果工具之间有依赖关系，模型应分多轮调用。

工具协作约束：代码探索遵循 `glob_files` → `grep_code` → `read_file`，已有文件修改遵循“定位 → 读取验证 → `apply_patch` 精确修改 → 测试/构建/诊断/回读验证”。`apply_patch` 默认要求 `old_string` 在当前文件中唯一命中；新建文件或明确需要整文件重写时才使用 `write_file`。`execute_command` 只用于构建、测试、Git 状态和受控诊断，不得以 `grep` / `rg` / `find` / `cat` 绕过对应工具的路径围栏与结果预算；长期开发服务必须用 `start_background_process`，随后优先用 `wait_background_process_ready` 根据日志识别服务就绪、端口占用或构建失败，必要时用 `read_background_process_log` 深入诊断、用 `stop_background_process` 停止，禁止用 `&` / `Start-Process` / `nohup` 脱离托管。服务日志在 `.yuforge/processes/`，只在本次 CLI 会话保留管理权，退出 CLI 会自动停止。当前项目代码问题不应优先联网，`search_code` 仅用于语义模糊或常规搜索无果的辅助检索。

工具卡片折叠展示调用对象，并以“探索 / 修改 / 验证 / 运行”标记开发阶段；执行结束后 ReAct、Plan 与 Multi-Agent Worker 都会额外显示一行脱敏终态摘要（完成、失败、超时或取消及耗时）。失败时只附稳定、无敏感原文的短恢复建议；原始工具输出只进入 Agent 上下文和调试日志，避免终端被大结果或敏感错误正文淹没。默认 inline 采用 append-only transcript：Thinking、工具详情和正文按事件到达顺序追加，Ctrl+O 仅在末尾展开最近块，不通过光标回退重绘旧历史，因此 Windows Terminal 缩放、全屏和标签切换不会造成历史行被覆盖或乱序。

实验性 inline 底部状态栏为无边框单行：空闲时显示 `model · cwd`，活动期显示 `Thinking <elapsed> · model · cwd`；宽度不足时优先保留模型。Windows Terminal 的缩放、全屏和标签恢复仍可能造成第三方终端对保留区的重排，因此默认关闭。

流式代码块在生成时显示稳定的 `generating code` 提示，完成后追加可用 `Ctrl+O` 展开的折叠块；不会再用 ANSI 光标回退覆盖已输出 transcript，避免长代码、宽字符换行或异步消息污染终端 scrollback。

ESC 语义按界面状态明确区分：输入期右提示显示 `Esc clear`，只清空当前编辑缓冲；Agent 运行期活动面板显示 `Esc cancel`，触发取消 token 并阻止后续 Agent 循环。已开始的网络 I/O 或工具执行会尽力中断，具体退出速度仍取决于 provider 与进程。

文件类与代码检索工具（`read_file` / `write_file` / `list_dir` / `glob_files` / `grep_code` / `create_project`）路径强制限定在项目根之内，越界请求会被策略层拒绝。首次启动未信任目录时，YuForge 会先展示独立的信任页；用户选择继续才加载该工作区，选择退出则不进入主界面。`execute_command` 通过命令黑名单拦截 `sudo` / `rm -rf 全盘` / `mkfs` / `dd of=/dev` / fork bomb / `curl|sh` 等。`revert_turn` 会批量回写工作区，默认触发 HITL 和审计。所有 `mcp__` 前缀工具默认触发 HITL 和审计。详见 `/policy`。

## 命令

进程级入口：

- `yuforge wechat setup` - 绑定微信 iLink 通道，选择 workspace 并完成扫码确认
- `yuforge wechat start` - 前台启动微信通道
- `yuforge wechat status` - 查看绑定状态和 daemon pid
- `yuforge wechat daemon start|stop|restart|status|logs` - 管理本机微信通道后台进程

交互式斜杠命令：

- `/wechat` - 扫码绑定并启动微信 iLink 通道；已绑定时直接启动
- `/wechat setup` - 重新扫码绑定并启动微信通道
- `/wechat status` - 查看当前 YuForge 进程内微信通道状态
- `/wechat stop` - 停止当前 YuForge 进程内微信通道
- `/plan` - 下一条任务使用 Plan-and-Execute 模式
- `/plan <任务>` - 直接用 Plan-and-Execute 模式执行这条任务
- `/team` - 下一条任务使用 Multi-Agent 协作模式
- `/team <任务>` - 直接用 Multi-Agent 协作模式执行这条任务
- `/cancel` - 运行中请求取消当前任务；空闲时会提示当前没有正在运行的任务
- `/hitl on` - 启用危险操作人工审批（HITL）
- `/hitl off` - 关闭 HITL 审批
- `/hitl` - 查看 HITL 当前状态
- `/mcp` - 查看所有 MCP server 状态
- `/mcp restart <name>` - 重启单个 MCP server
- `/mcp logs <name>` - 查看 MCP server 最近 200 行 stderr 日志
- `/mcp disable <name>` - 运行时禁用 MCP server 并移除其工具
- `/mcp enable <name>` - 运行时启用 MCP server
- `/mcp resources <name>` - 查看 MCP server 暴露的 resources
- `/mcp prompts <name>` - 查看 MCP server 暴露的 prompts（只查看，不注入对话）
- `/policy` - 查看安全策略状态（路径围栏 / 命令黑名单 / 资源上限 / 审计目录）
- `/doctor` - 只读检查工作区、Java、Git、ripgrep、按项目类型需要的 Maven 或 Node/npm、当前模型 API Key 配置和 MCP 就绪摘要；不会验证 API 连通性、安装依赖或运行项目
- `/audit [N]` - 查看今日最近 N 条危险工具审计记录（默认 10）
- `/snapshot` - 查看最近 Side-Git 快照
- `/snapshot status` - 查看 Side-Git 快照状态
- `/snapshot clean` - 清理当前项目 Side-Git 快照目录
- `/restore <N>` - 恢复到最近第 N 个 pre-turn 快照
- `/memory` / `/mem` - 查看记忆系统状态
- `/memory list` - 查看长期记忆列表
- `/memory search <关键词>` - 搜索当前项目可见长期记忆
- `/memory delete <id>` - 删除单条长期记忆
- `/memory clear` - 清空长期记忆
- `/save <事实>` - 手动保存项目级关键事实到长期记忆；`/save --global <事实>` 保存跨项目通用偏好
- `save_memory` - Agent 内置工具，仅在用户明确要求保存长期偏好或稳定事实时调用；默认 `scope=project`，跨项目通用偏好才用 `scope=global`
- `/init` - 生成精简项目级记忆 `YUFORGE.md`；已存在时不覆盖，`/init --force` 可重写
- `/export` - 导出当前 ReAct 会话对话记录为 Markdown（包含完整 system prompt），写入 `~/.yuforge/exports/session-*.md`
- `/index [路径]` - 索引代码库（默认当前目录）
- `/search <查询>` - 语义检索代码（RAG 辅助路径）
- `/graph <类名>` - 查看代码关系图谱
- `/clear` - 清空当前对话历史、短期记忆、待注入 Skill 上下文和上一轮检索记忆注入；长期记忆保留
- `/compact` - 立即把旧历史压缩为结构化工程检查点，保留最近 1 个 user 轮次
- `/exit` / `/quit` - 退出程序

## 运行效果

### 第一期：旧版启动效果

```text
╔══════════════════════════════════════════════════════════╗
║                                                          ║
║   ██████╗  █████╗ ██╗      ██████╗██╗     ██╗            ║
║   ██╔══██╗██╔══██╗██║     ██╔════╝██║     ██║            ║
║   ██████╔╝███████║██║     ██║     ██║     ██║            ║
║   ██╔═══╝ ██╔══██║██║     ██║     ██║     ██║            ║
║   ██║     ██║  ██║███████╗╚██████╗███████╗██║            ║
║   ╚═╝     ╚═╝  ╚═╝╚══════╝ ╚═════╝╚══════╝╚═╝            ║
║                                                          ║
║              简单的 Java Agent CLI v1.0.0                ║
║                                                          ║
╚══════════════════════════════════════════════════════════╝
```

### 第三期：当前运行效果

```text
   ██    ██  ██    ██    YuForge YU  v16.1.0
    ██  ██   ██    ██    Model glm-5.1 (glm)
     ████    ██    ██    MCP 4/4 · 61 tools · 2/2 skills · ReAct
      ██     ██    ██    ReAct · Plan · MCP · Browser · Image
      ██      ██████

Tips for getting started:
1. Type / for commands and Tab completion
2. Ask coding questions, edit code or run commands
3. Attach context with @path or @image:

* 你好，请列出当前目录的文件

🧠 思考过程:
用户想了解当前目录结构。我先读取目录，再基于结果做归类说明，而不是只回原始文件列表。

🤖 最终结果:
当前目录包含 `src`、`target`、`pom.xml`、`README.md` 等文件，
这是一个标准的 Java Maven 项目。

* /exit

👋 再见!
```

## 技术栈

- Java 17
- Maven
- GLM-5.1 API
- OkHttp
- Jackson
- JLine 4（终端交互、Status、输入 widgets）
- SQLite（向量与图谱持久化）
- JavaParser（AST 分析）
- Ollama（本地 Embedding）

## 项目结构

```
src/main/java/com/yuforge
├── agent/
│   ├── Agent.java              # ReAct Agent
│   ├── PlanExecuteAgent.java   # Plan-and-Execute Agent
│   ├── AgentRole.java          # Agent 角色枚举
│   ├── AgentMessage.java       # Agent 间通信消息
│   ├── SubAgent.java           # 可配置子代理
│   └── AgentOrchestrator.java  # Multi-Agent 编排器
├── cli/
│   ├── Main.java               # CLI 入口
│   ├── CliCommandParser.java   # 命令解析
│   └── PlanReviewInputParser.java  # 计划审核输入
├── llm/
│   ├── GLMClient.java          # GLM API 客户端；glm-5.1 走 Coding endpoint，glm-5v-turbo 走多模态 endpoint
│   ├── DeepSeekClient.java     # DeepSeek API 客户端
│   ├── StepClient.java         # 阶跃星辰 StepFun API 客户端
│   ├── KimiClient.java         # Kimi / Moonshot API 客户端
│   ├── FreeLlmApiClient.java   # 本地 FreeLLMAPI OpenAI-compatible 网关客户端
│   └── AgnesClient.java        # Agnes AI OpenAI-compatible 客户端
├── context/
│   ├── ContextProfile.java     # 模型窗口与上下文策略
│   └── TokenUsageFormatter.java # Token / cache / 成本展示
├── memory/
│   ├── MemoryEntry.java        # 记忆条目
│   ├── ConversationMemory.java # 短期记忆
│   ├── LongTermMemory.java     # 长期记忆
│   ├── ContextCompressor.java  # 上下文压缩
│   ├── ConversationHistoryCompactor.java # 实际 LLM 历史分层治理
│   ├── ToolResultArtifactStore.java # 旧工具结果会话级归档与恢复
│   ├── TokenBudget.java        # Token 预算管理
│   ├── MemoryRetriever.java    # 记忆检索
│   └── MemoryManager.java      # 记忆门面类
├── plan/
│   ├── Task.java               # 任务定义
│   ├── ExecutionPlan.java      # 执行计划
│   └── Planner.java            # 规划器
├── rag/
│   ├── EmbeddingClient.java    # Embedding API 客户端
│   ├── VectorStore.java        # SQLite 向量存储
│   ├── CodeChunk.java          # 代码块模型
│   ├── CodeChunker.java        # 代码分块器
│   ├── CodeAnalyzer.java       # AST 关系分析
│   ├── CodeRelation.java       # 代码关系模型
│   ├── CodeIndex.java          # 索引管理器
│   └── CodeRetriever.java      # 检索入口
└── tool/
    └── ToolRegistry.java       # 工具注册表
```
