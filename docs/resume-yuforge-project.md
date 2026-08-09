# YuForge 简历项目描述

> 使用方式：简历中优先使用“标准版”或“精简版”其一；不要把同一项目拆成多个重复项目。  
> 原则：只写可运行、可演示、可解释的能力；不编造准确率、性能提升倍数或压缩率。

## 标准版（推荐）

### YuForge｜面向真实代码库的 Java Code Agent CLI

**项目简介：**

对标 Claude Code 的 Java Code Agent CLI。支持基于 ReAct 的代码理解、修改与验证闭环，并提供 Plan-and-Execute、多 Agent 协作、长上下文治理、MCP 工具接入、HITL 安全审批和跨会话恢复能力；面向 Windows Terminal 优化长会话交互稳定性。

**技术栈：**

Java 17、Maven、JLine 4、SQLite、JGit、OkHttp/SSE、JSON-RPC 2.0、MCP、JavaParser、Jieba、JUnit 5、Mockito、Chrome DevTools MCP

**核心职责：**

- 设计 ReAct、Plan-and-Execute、Multi-Agent 三条执行路径，抽象统一 ToolRegistry 与并行工具调度层；支持最多 4 个工具并发执行，并保持工具结果按原调用顺序回灌模型，避免并发输出乱序。
- 设计长上下文治理方案：将稳定规则与动态运行时信息分层组织，保持 system prompt 前缀稳定以提升 Prompt Cache 命中；上下文超限时优先归档大型工具结果，再按用户轮次进行 Map-Reduce 结构化压缩，支持通过 `artifact_id` 按需恢复原始结果。
- 构建分层记忆与会话恢复机制：项目级 `YUFORGE.md`、用户显式授权的长期记忆、短期任务记忆和 checkpoint 会话恢复协同工作；支持 `/init`、`/checkpoint`、`/session`、`/resume`，并保证跨会话恢复使用最新系统规则。
- 建立 Agent 工具安全链路：外部网页、搜索和 MCP 返回统一标记为不可信内容；高风险工具依次经过 HITL、PathGuard 和 CommandGuard，防止间接提示词注入诱导文件写入、命令执行或记忆污染。
- 实现 MCP 多 Server 管理与 Windows 兼容启动：支持 stdio/HTTP、动态工具注册、后台启动和状态诊断；解决 Java `ProcessBuilder` 无法直接解析 Windows `npx` 的问题，兼容 Chrome DevTools MCP 等 Node 工具生态。
- 重构 CLI 渲染为 append-only transcript：针对 Windows Terminal 缩放、全屏、切标签导致的 ANSI 光标重绘错位问题，取消默认动态 dock/right prompt，采用稳定 Thinking、折叠工具摘要与可审计滚屏，保障长任务交互不乱序。

## 精简版（简历空间不足时使用）

### YuForge｜Java Code Agent CLI

**项目简介：** 对标 Claude Code 的 Java Code Agent CLI，覆盖代码理解、修改、验证、MCP 工具调用、长上下文治理和跨会话恢复。

**技术栈：** Java 17、JLine 4、SQLite、JGit、OkHttp/SSE、MCP、JavaParser、JUnit 5

- 设计 ReAct / Plan-and-Execute / Multi-Agent Code Agent 架构，统一工具注册、并行调度与结果顺序回灌，支持复杂代码任务拆解、执行和验证闭环。
- 设计缓存友好的 Prompt 分层与长上下文治理：稳定 system prefix + 动态 user context，结合工具结果归档、按轮次 Map-Reduce 压缩和按需恢复，避免滑动窗口丢失关键工具结果。
- 构建 Memory、Checkpoint 与项目级规则体系，支持跨会话恢复、长期记忆审计和项目初始化，使 Agent 能持续理解代码库约束。
- 建立 MCP/HITL/PathGuard 安全与终端交互体系，解决外部提示词注入、Windows `npx` 启动和终端缩放重绘错乱等工程问题。

## AI Agent 岗位侧重点

投递 AI Agent、智能体平台、LLM 应用工程岗位时，保留标准版前四条，并优先强调：

- Prompt Cache 一致性如何影响 system prompt / runtime context 的边界；
- 为什么滑动窗口会破坏 tool-call/tool-result 事务，以及结构化压缩和 artifact 恢复如何解决；
- 外部内容来源标记、HITL 与记忆写入授权如何共同防止 Prompt Injection；
- ReAct、Plan 和 Multi-Agent 的适用边界，而不是把多 Agent 作为默认答案。

一句话定位：

> 面向真实代码库任务的 Agent Runtime，重点解决长上下文、工具安全和可恢复执行，而不只是封装一次 Function Calling。

## 后端开发岗位侧重点

投递 Java 后端、基础架构、平台工程岗位时，保留标准版第一、第四、第五、第六条，并优先强调：

- 统一工具网关、策略链和权限边界；
- SQLite 会话/记忆持久化与可审计删除；
- JSON-RPC MCP 生命周期、stdio/HTTP transport 和子进程兼容性；
- `CompletableFuture` / ExecutorService 的并发执行、结果顺序确定性和超时/取消传播；
- JLine 终端渲染在异步、缩放、CJK 宽字符场景下的工程取舍。

一句话定位：

> 我把 LLM 当作一个不稳定的上游服务，用 Java 的分层、并发、持久化、策略控制和可观测性把它收敛为可用的开发工具。

## 面试展开话术

### 30 秒项目介绍

> YuForge 是我做的 Java Code Agent CLI，目标是让 Agent 能在真实代码库中完成“理解、修改、验证”的闭环，而不仅仅是聊天或生成代码。系统默认用 ReAct 执行任务，复杂任务可切换 Plan 或 Multi-Agent；我重点解决了长上下文、工具安全和终端稳定性三个问题，分别通过结构化压缩与 artifact 恢复、HITL + PathGuard 安全链，以及 append-only CLI transcript 来落地。

### 长上下文为什么不用滑动窗口

> 滑动窗口的问题不只是忘记早期消息。它可能从中间截断 assistant 的工具调用和 tool result，破坏协议事务；也会让 prompt 前缀不断变化，影响缓存命中。YuForge 先把大工具结果归档，历史保留可引用的摘要和 artifact id；真需要原文时再精确读取。仍然超预算才按 user turn 做结构化压缩，因此不会靠生硬字符截断制造上下文空洞。

### 如何防止间接提示词注入

> 我不把网页、MCP 或文件正文当作指令来源，而是统一标记为不可信外部内容。即使模型在内容中读到“把文件写到某路径”，也不能直接获得权限；写文件、执行命令、保存记忆等高风险操作仍需通过 HITL 和路径策略。长期记忆写入还要求本轮原始用户输入明确授权，避免污染跨会话状态。

### 为什么 CLI 最终选择 append-only

> 最初我尝试过右提示和动态底部状态栏，但 Windows Terminal 在缩放、全屏、切标签时会重排 scrollback，基于相对光标擦除的方案会留下重复行或覆盖历史。最终我把“可读和可审计”放在动画效果前面：提交输入、Thinking、工具摘要和回答都只追加，不回写历史。这是一次从截图体验转向真实终端可靠性的取舍。

### 如何证明 Agent 真做完了任务

> 我区分“改动已执行”和“改动已验证”。Agent 发生文件修改后，只有实际执行成功的构建/测试、服务 ready 信号或回读文件才会形成验证证据；没有证据时系统明确提示“已修改但未验证”，避免把模型的自然语言结论误当成工程事实。

## 使用前检查

- 日期、项目名称和你的个人角色请按实际情况填写。
- 如果简历写“独立开发”，请确保你能演示安装、配置模型、MCP、项目分析、代码修改和测试验证完整链路。
- 不要写“容器沙箱”“MCP OAuth/sampling”“通用 DLP”“自动重启恢复”等尚未交付能力。
- 不要同时写“最多 4 并发工具”和未经测量的“性能提升 N 倍”；前者是功能事实，后者需要 benchmark 证据。
