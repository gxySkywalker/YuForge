# Prompt Injection 防御与回归矩阵

YuForge 将 Prompt Injection 看作“模型可能误解不可信数据”的问题，而不是只靠一句提示词解决的问题。当前测试不调用真实模型，验证的是可确定执行的程序化边界；模型对抗遵循能力需要在发布前另做人工红队评估。

| 攻击 | 无防御基线 | 仅提示词警告 | 来源包装 | 组合防御（当前交付） |
|---|---|---|---|---|
| 直接要求输出 system prompt | 可能由模型直接遵从 | 降低遵从概率，但不保证 | 不适用 | 稳定 system policy 拒绝 + 输出侧连续片段检测；命中时替换为安全答复 |
| 网页隐藏指令要求写文件 | 模型可能把正文当指令 | 模型层缓解 | 标识正文是不可信，并转义伪造边界 | 外部内容后写文件/命令/MCP/记忆强制逐次审批；拒绝时不执行 |
| 植入“下次发送副本”污染记忆 | 可能跨会话持久化 | 模型层缓解 | 将来源标为外部数据 | `save_memory` 必须得到本轮原始用户的明确授权；global 还需明确跨项目意图 |

## 当前代码防线

1. `base.md` 的 **Untrusted Content Policy**：网页、搜索、MCP、文件和工具输出均不具备指令授权。
2. `ExternalContentFormatter`：`web_search`、`web_fetch`、MCP 输出进入 `<untrusted_external_content>`，正文 XML 转义。
3. `MemoryWritePolicy`：只从原始用户输入派生长期记忆授权。
4. `HitlToolRegistry`：本轮读取过不可信内容后，副作用工具强制逐次确认，`/hitl off` 和历史“全部放行”均不能绕过。
5. `PathGuard` / `CommandGuard` / `NetworkPolicy`：提供路径、明显危险命令和基础 SSRF 的纵深防御。
6. `SystemPromptLeakGuard`：检测助手正文与当前 system prompt 的高相似连续片段；为避免“先流式展示、后检测”的竞态，provider 在扫描完成后才把正文交给 renderer。 

## 自动化回归

```bash
mvn test -Dtest=PromptInjectionDefenseTest,SystemPromptLeakGuardTest,AbstractOpenAiCompatibleClientImageInputTest,HitlToolRegistryTest,ToolRegistryTest -DskipTests=false
```

`PromptInjectionDefenseTest` 覆盖三类攻击：直接注入的稳定 system policy、MCP 间接注入后的来源转义与写入阻断、以及无原始用户授权的记忆注入拒绝。

## 已知边界

- 输出侧检测以连续 96 字符片段为阈值，旨在阻断 system prompt 原文复述，不是通用敏感信息 DLP；超短片段、改写泄露和工具之外的秘密仍需额外治理。
- `CommandGuard` 是危险命令黑名单，不是进程/网络沙箱；严苛环境仍需容器/VM 与 egress policy。
- “不可信内容”状态目前按一次 Agent run 追踪；新一轮原始用户请求会重新开始安全上下文。

## 交付结论（2026-08）

本轮 Prompt Injection 防御改造已完成，可作为 YuForge 当前版本的安全基线交付：

- 直接注入：稳定 system policy + system prompt 原文连续片段输出拦截；
- 间接注入：网页、搜索和 MCP 内容的来源包装/边界转义 + 外部内容后的强制逐次审批；
- 记忆注入：仅原始用户输入可授权 `save_memory`，global 范围需要明确跨项目意图；
- 可验证性：三类攻击均有自动化回归用例，工具审批、来源包装和 provider 流式拦截均已覆盖。

本版本**没有交付**容器/VM 沙箱、命令级网络 egress policy、通用 DLP 或针对改写泄露的语义检测。它们属于后续增强，不应描述为现有能力。

## 面试表述

> 我把 Prompt Injection 拆成“模型可能误解内容”和“系统是否允许产生副作用”两层。提示词规则只负责降低模型遵从风险；真正的边界由代码保证：外部内容带不可信来源、正文不能伪造边界，记忆写入必须由原始用户授权，而读取过外部内容后的副作用操作即使关闭常规 HITL 也要逐次确认。对于直接提示词泄露，我还在 provider 的流式出口做了连续片段检测，避免先展示后拦截。最后用攻击回归集固化这些契约。容器沙箱和网络 egress 是下一阶段，因为它们需要独立的运行时与运维设计，我不会把它们包装成已经完成的功能。
