# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 入口约定

本仓库的协作入口是 `AGENTS.md`，首次进入请优先阅读 `AGENTS.md`，详细行为见 `docs/agents-reference.md`。本文件是 Claude Code 特供快速参考，不重复 `AGENTS.md` 已有内容。

信息优先级：代码实际行为 > `AGENTS.md` > `YUFORGE.md`（项目记忆，启动时注入）> `README.md` > `ROADMAP.md` > `CLAUDE.md`。`ROADMAP.md` 代表演进方向，不代表已交付。

## 角色与协作方式

本项目主要由 Codex 编写，你的默认角色是 **Code Reviewer**，其余角色需要显式指令才生效。

| 角色 | 何时生效 | 行为 |
|------|---------|------|
| **Code Reviewer**（默认） | 没有其他指令 | 审 Codex 的改动：架构问题 / bug / 并发风险 / 安全问题 / 性能 / 测试缺口 / 可维护性，讲清根因与改进建议。**只审不改**，除非被明确要求修改。 |
| **Secondary Developer** | Codex 不可用 / 配额耗尽 / 你明确要求实现 | 动手前：`git status` + `git diff`、读现有实现、理解架构与代码风格；最小改动，不做不必要的重构。 |
| **Interview Coach** | 你明确要求面试 / 讲解 / 演示准备 | 深挖实现与设计动机，准备项目背景、技术设计、实现细节、取舍、失败场景、性能表现，以及面试追问的简短可答话术。 |

### 行为准则

- 不虚构项目行为：结论只来自源码 / 配置 / Git 历史 / 测试 / 文档；无法确认的明确说「不确定」。
- 区分「现状实现 / 建议改进 / 未来可能设计」，不要混为一谈。
- 审代码时不主动出面试题，除非被要求。

### 审阅流程

`git status` → `git diff` 或目标提交 → 读相关源码 → 读相关测试与配置 → 总结实际实现 → 审正确性 / 架构 / 风险 → 给出改进建议。

### 开发流程

先理解现有架构 → 改动前先说明计划 → 增量修改 → 有测试就跑 → 最后总结：改动文件、实现思路、潜在风险。

## 快速命令

```bash
cp .env.example .env                     # 首次配置 API Key（至少一个 provider 即可）
mvn clean package                        # 编译，默认跳过测试，产出可验收 shaded jar
java -jar target/yuforge-1.0-SNAPSHOT.jar # 运行
mvn test -Pquick                         # 常规快速回归（排除慢的外部进程/网络测试）
mvn test -Pphase16-smoke                 # TUI / inline renderer 冒烟
mvn test -Dtest=XxxTest -DskipTests=false # 单测
mvn test -DskipTests=false               # 全量回归
git tag vX.Y.Z && git push origin vX.Y.Z  # 触发 GitHub Actions 构建 jar 并创建 Release
```

## 架构速览

三条主执行路径，共享 ToolRegistry / MemoryManager / SnapshotService / 渲染与审计基础设施：

| 路径 | 入口 | 触发 |
|------|------|------|
| ReAct | `Agent.java` | 默认 |
| Plan-and-Execute | `PlanExecuteAgent.java` | `/plan` |
| Multi-Agent | `AgentOrchestrator.java` | `/team` |

核心包（完整仓库结构见 `AGENTS.md`）：
- `agent/` `plan/` — 三种执行模式、Planner / ExecutionPlan / Task
- `cli/` — Main、命令解析、Plan 审阅、JLine 补全 / 高亮 / 历史
- `llm/` — 多模型客户端（GLM / DeepSeek / Step / Kimi / FreeLLMAPI / Xfyun / Agnes）+ `LlmClientFactory`
- `tool/` — ToolRegistry，内置 26 个工具 + MCP 动态工具（`mcp__{server}__{tool}`）
- `mcp/` — MCP 协议（stdio + Streamable HTTP）
- `memory/` `context/` — 短期/长期记忆、上下文压缩、工具结果归档、checkpoint
- `rag/` — `search_code` 语义检索（精确代码定位仍走 glob_files / grep_code / read_file）
- `lsp/` — 写文件后 post-edit 诊断注入
- `prompt/` — Prompt 分层组装；内置 prompt 在 `src/main/resources/prompts/`，`~/.yuforge/prompts/` 与 `.yuforge/prompts/` 可覆盖
- `snapshot/` — Side-Git 快照与回滚（快照仓库在 `~/.yuforge/snapshots/`，不写项目 `.git`）
- `hitl/` `policy/` — 拦截顺序 HitlToolRegistry → ToolRegistry → PathGuard / CommandGuard
- `render/` `tui/` — 三形态渲染（inline 默认 / lanterna 全屏 / plain 兜底）
- `browser/` `web/` — Chrome DevTools MCP 桥接 / 搜索与抓取（SearchProvider / WebFetcher）
- `runtime/` — Runtime API（仅 127.0.0.1 + API Key）+ 后台任务（SQLite）
- `wechat/` — 微信 iLink 通道（默认不开启）
- `skill/` — Skill 系统

## 修改联动规则（摘要）

- 改命令入口 → `Main.java` + `CliCommandParser.java` + 测试 + `README.md` + `AGENTS.md`
- 改工具集 → `ToolRegistry.java` + Agent / PlanExecuteAgent / SubAgent 提示词 + 文档
- 改模型/接口 → 对应 Client + `LlmClientFactory.java` + `.env.example` + 文档
- 改 MCP → `mcp/` + ToolRegistry + HITL + 提示词 + 测试
- 改行为 → 同步 `AGENTS.md` / `README.md`；`ROADMAP.md` 仅状态变化时更新
- 不提交 `.env` / API Key / `target/` 产物；不改行为时不要顺手重构
