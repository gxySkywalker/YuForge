# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 入口约定

本仓库的协作入口是 `AGENTS.md`，首次进入请优先阅读 `AGENTS.md`。本文件提供 Claude Code 特供的快速参考，不重复 `AGENTS.md` 已有内容。

## 快速命令

```bash
cp .env.example .env                     # 首次配置 API Key
mvn clean package                        # 编译（默认跳过测试）
java -jar target/yuforge-1.0-SNAPSHOT.jar # 运行
mvn test -Pquick                         # 常规快速回归
mvn test -Dtest=XxxTest -DskipTests=false # 单测
mvn test -Pphase16-smoke                 # TUI 相关冒烟
mvn test -DskipTests=false               # 全量回归
```

## 架构速览

三条主执行路径，共享 ToolRegistry / MemoryManager / SnapshotService：

| 路径 | 入口 | 触发 |
|------|------|------|
| ReAct | `Agent.java` | 默认 |
| Plan-and-Execute | `PlanExecuteAgent.java` | `/plan` |
| Multi-Agent | `AgentOrchestrator.java` | `/team` |

核心包：
- `agent/` — Agent 主循环、子代理、编排器
- `cli/` — Main 入口、命令解析、Plan 审阅交互
- `llm/` — 多模型客户端（GLM / DeepSeek / Step / Kimi / FreeLLMAPI / Agnes）
- `tool/` — ToolRegistry，内置 11 个工具 + MCP 动态工具
- `mcp/` — MCP 协议（stdio + Streamable HTTP）
- `memory/` — 短期/长期记忆、上下文压缩
- `render/` — 双形态渲染（inline 默认 / lanterna 全屏 / plain 兜底）
- `prompt/` — Prompt 分层组装（base → personality → mode → ... → handoff）
- `snapshot/` — Side-Git 快照与回滚
- `wechat/` — 微信 iLink 通道
- `skill/` — Skill 系统
- `policy/` — PathGuard / CommandGuard / AuditLog

## 修改联动规则（摘要）

- 改命令入口 → `Main.java` + `CliCommandParser.java` + 测试 + 文档
- 改工具集 → `ToolRegistry.java` + Agent 提示词 + 文档
- 改模型/接口 → 对应 Client + `LlmClientFactory.java` + `.env.example` + 文档
- 改 MCP → `mcp/` + ToolRegistry + HITL + 提示词 + 测试
- 不改行为时不要顺手重构；不提交 `.env` / API Key / `target/` 产物
