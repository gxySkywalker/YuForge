# YuForge 上下文与记忆工程设计说明

> 目标：在 Code Agent 的长任务中，控制上下文成本的同时，尽量不丢失关键事实、工具结果与任务进度；并使行为可缓存、可恢复、可审计、可验证。

## 1. 问题与设计约束

简单的滑动窗口会直接删除最早消息。它的问题不只是“少了一些历史”：

- 删除前缀会改变后续请求的 token 前缀，降低 Prompt Cache 命中；
- 被滑出的工具结果可能仍是后续决策的证据，Agent 容易重复调用工具或基于不完整信息猜测；
- 删除策略不可审计，出现错误时无法判断是模型推理、检索还是上下文治理造成的。

YuForge 因此把上下文治理当成架构约束，而不是在 prompt 过长后才做的字符串截断。核心原则是：**稳定内容保持稳定，动态内容按轮注入；大结果可恢复，长期事实可审计；每一层都能度量和回退。**

## 2. 分层架构

```text
稳定 system 前缀
  base → personality → mode → approval → context management → handoff
  → project stable context (YUFORGE.md) → skills

当前 user turn（动态）
  runtime environment + TODO + relevant memory + MCP resource index + user input

会话与恢复层
  conversationHistory → artifact archive → structured compaction → checkpoint/resume

长期记忆层
  project/global scope → retrieval strategy → token-budgeted injection → trace/golden set
```

`PromptAssembler` 固定 system prompt 的拼接顺序；`RuntimeContextFormatter` 将日期、时区、工作目录、Shell、相关记忆、MCP resource 等易变信息放入本轮 user message。因此日常对话不会因这些动态字段反复破坏稳定前缀。

## 3. 上下文治理链路

### 3.1 不使用消息数量滑动窗口

`ConversationHistoryCompactor` 依据模型 context window 和安全缓冲在高水位触发治理，而不是“只保留最近 N 条”。先保留近期完整事务与 tool-call/tool-result 边界，再压缩旧历史为结构化工程检查点。

### 3.2 先归档，再压缩

对于大型 `tool_result`，`ToolResultArtifactStore` 保存有界原文，消息历史保留包含 `artifact_id` 和 preview 的协议安全占位。模型在确实需要证据时可通过 `read_tool_artifact` 精确读取，而不用让每轮请求一直携带大文本。

工具失败还会经过 `ToolResultDiagnostic` 归一化为错误码、是否可重试和恢复建议。`ToolAttemptTracker` 以“工具名 + 规范化参数”计数：同一失败第二次要求换策略，第三次禁止原样重试，降低“忘记结果后重复调用”的循环风险。

### 3.3 会话恢复边界

`/checkpoint` 保存文本协议历史、TODO 与必要会话元数据；`/resume <session_id>` 仅允许恢复相同项目。原始工具结果、图片 payload、system prompt 不跨会话持久化：前两者可能过大或敏感，system prompt 则必须使用当前版本重新构建。恢复后工具 artifact 归档清空，避免将旧会话的临时证据误当作新会话事实。

## 4. 长期记忆：少写、隔离、可解释

长期记忆只通过 `/save` 或用户明确要求保存，默认 project scope；真正跨项目的偏好才使用 global。`/memory list/search/delete/clear` 可审计和删除，`clear --global` 与 `clear --all` 必须显式指定。

召回时：

1. 仅搜索当前项目可见的 project + global 事实；
2. 空查询不召回任何记忆；
3. 默认关键词 + metadata 轻量加权；
4. 按相关度、时间、ID 确定性排序；
5. 按本轮 token 预算注入到 `<relevant-memory>`，不重写 system prompt。

### 可演进策略

`MemoryRetrievalStrategy` 将“候选排序”与上下文注入解耦。当前 `KeywordMemoryRetrievalStrategy` 零外部依赖、离线可用，是可靠默认值。后续 Hybrid 策略可在同一接口中融合 keyword 与 embedding 候选，但必须满足：

- embedding 服务不可用时回退关键词策略；
- 不改变 project/global 隔离和 token 预算；
- 继续输出相同的 Trace；
- 扩充 Golden Set 后证明没有检索质量回退。

这避免为了引入向量检索而把网络依赖、索引维护和故障模式带进 Agent 主循环。

## 5. 可观测性与验证

每轮长期记忆注入都会生成 `MemoryRetrievalTrace`，可从 `/memory status` 或 `/context` 查看：query token 数、候选数、注入的 memory id/score/token，以及因预算跳过的候选。Trace 不保存原始用户查询和记忆正文，也不会进入模型消息。

`MemoryRetrievalGoldenSetTest` 目前覆盖以下不可退化契约：

| 维度 | 断言 |
|---|---|
| 相关性 | 多关键词命中事实排在部分命中前 |
| 隔离 | 其他项目的私有事实绝不召回 |
| 空查询 | 不会把整个记忆库塞进上下文 |
| 稳定性 | 同分按时间、ID 排序，持久化与列表顺序稳定 |
| 预算 | 被截断候选出现在 Trace，而不是静默消失 |
| 可替换性 | 自定义策略可复用既有注入和 Trace 协议 |

当前针对性回归命令：

```bash
mvn test '-Dtest=MemoryRetrieverTest,MemoryManagerTest,MemoryRetrievalGoldenSetTest,LongTermMemoryTest' -DskipTests=false
```

## 6. 面试讲解版本

> 我没有用“保留最近 N 条消息”的滑动窗口，因为它既破坏缓存前缀，又可能删除工具调用得到的关键证据。YuForge 将稳定 prompt 与动态 runtime context 分层，工具大结果先归档、按需恢复，历史过长再生成结构化检查点。长期记忆坚持显式写入、项目隔离、确定性检索，并用 token 预算控制注入。最后我用 Golden Set 和检索 Trace 把这条链路变得可验证、可排障。向量混合检索被设计为可替换策略，但没有在缺少评测和降级策略时贸然启用。

## 7. 当前边界与后续条件

- 当前长期记忆不是向量数据库；这是有意的默认选择，不应宣传为已启用 hybrid retrieval。
- `MemoryRetrievalTrace` 是本地诊断，不是用户数据分析系统；不上传、不进入模型上下文。
- 当收集到更多匿名、可公开的检索样例，并补齐 embedding 索引增量更新、可用性探测、超时与关键词回退后，再启用 Hybrid Retrieval。
