# YuForge 取消与打断机制

## 交付结论

YuForge 当前已交付“同步 LLM 请求的真实取消”：inline CLI 的 `ESC`、TUI `/cancel`、微信会话取消都会向 Agent、工具和 OpenAI-compatible provider 的底层 OkHttp 请求传播取消信号。正在阻塞的 SSE 请求会调用 `Call.cancel()`，而不是只能等待模型自然返回或网络超时。

## 为什么需要这一层

仅调用 `Future.cancel(true)` 并不能可靠停止同步网络 I/O。此前 Agent 虽然会在 LLM 返回前后检查 `CancellationContext`，但 `OkHttp Call.execute()` 仍可能持续阻塞；用户只能取消后续循环，无法及时停止正在生成的模型请求。

## 取消传播链路

```text
ESC / TUI /cancel / 微信 cancel
  → CancellationToken.cancel()
  → Agent / Plan / Multi-Agent / ToolRegistry 协作式检查
  → 已注册的 OkHttp Call.cancel()
  → SSE socket 断开，client 映射为“LLM 调用已取消”
```

`CancellationToken` 提供一次性、线程安全的取消回调注册。`AbstractOpenAiCompatibleClient` 在创建每个 OkHttp `Call` 后注册 `call::cancel`，请求正常结束后注销回调，避免跨请求误取消。所有内置文本 provider 复用该公共客户端，因此行为一致。

## 语义与边界

- 取消是尽力而为的网络中断：实际生效速度仍受 provider、TCP 连接和本地网络栈影响。
- 取消发生后，Agent 不再执行下一轮推理或后续工具调用。
- inline CLI 任务执行期间的即时取消入口是 `ESC`；TUI 使用 `/cancel`；微信会话由其 session cancel 调用触发。
- `execute_command` 等本地工具仍保留各自超时和中断逻辑。

## 验证

```bash
mvn test -Dtest=CancellationContextTest,AbstractOpenAiCompatibleClientImageInputTest,AgentStreamRendererTest,AgentBudgetTest -DskipTests=false
```

测试包含一个延迟 SSE 响应：请求建立后触发 `CancellationToken.cancel()`，断言同步调用在正文到达前以“LLM 调用已取消”返回。

## 暂不交付：事件队列与后台工具续跑

完整的“用户插话但工具继续后台执行”需要会话 Actor、事件队列、工具生命周期、延迟结果处理和消息历史单写者约束。尤其是工具调用已经写入 assistant message 后被打断时，不能为同一个 `tool_call_id` 写两份 tool result。

后续若实现，应使用以下状态迁移：

```text
RUNNING tool call
  → BACKGROUND_PENDING / CANCELLED tool result
  → urgent user event
  → late_tool_completion（独立事件，不复用原 tool_call_id）
```

非紧急事件应进入队列，并以带序号的 `<pending_events>` 结构一次性注入，避免模型只关注最后一条事件。该方案有较高工程价值，但当前不作为秋招项目的必交付能力。

## 面试表述

> 我把取消分成两层：Agent 层的协作式取消用于停止后续推理和工具调用，网络层的 `Call.cancel()` 用于真正打断阻塞的同步 SSE 请求。两者通过取消 token 的回调机制连接，因此 CLI、TUI 和微信通道复用同一条链路。更复杂的插话与后台工具续跑需要事件队列和严格的 tool-call 协议状态机，我已经完成设计边界，但没有在缺少完整一致性保证时贸然上线。
