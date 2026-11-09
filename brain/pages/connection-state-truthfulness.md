---
id: connection-state-truthfulness
title: "连接状态与测速必须如实反映上游, 不得用可达性冒充可用性"
category: decision
status: active
tags: [diagnostics]
created: "2026-11-10T01:09:47"
updated: "2026-11-10T01:12:21"
---

## compiled_truth

**问题**：软件自测总报「连接成功」，而生产节点实际不可用。根因是**同一个病的三张脸**：

1. `isHealthy()` 在内核算出、经 AIDL 导出，但 P3 之后**没有任何 Kotlin 消费者**；
2. 默认测速只做 **TCP 连通性**，TCP 连上不代表协议可用；
3. 隧道 RTT 读的是一个**没人写入**的 atomic。

**修法**：
- 健康位接回 UI，并加**迟滞**：`LAST_UNHEALTHY_MS` + `UNHEALTHY_STICKY_MS = 40_000`，单调时钟，只有 `is_healthy() && engine.latency_ms().is_some()` 才清除；**`stop()` 里刻意不清**（停机不该洗白上一次的不健康）。
- 默认测速改走**真实握手**（`METHOD_CONNECT`）；RTT 改成 `.and_then(|n| n.latency_ms("rtt"))` 接回暖池统计。
- 池超时日志带握手标志：`Timeout before CCS (sh={}, ccs={}, enc={})`。

**自己引入又修掉的回归**：默认改 `connect` 后，内核没运行时一律报「不可用」。降级规则：
```kotlin
internal fun effectiveMethod(requested: String, coreRunning: Boolean): String =
    if (requested == METHOD_CONNECT && !coreRunning) METHOD_TCP else requested
```
并在 UI **如实标注**这次是 TCP-only（`latencyTcpOnly`），不冒充握手结果。

**两次把自己的修复误判为坏掉**：我两度断言健康指示器失灵，实际是内核已自动切到健康的测试节点，`healthy=true` 是真话。诊断必须先确认内核当前选的是哪个节点。

**生产节点的真实结论**：`sh=false`——TCP 4ms 连通后服务端零字节，节点侧问题，与协议版本无关（见 [[protocol-break-v015]]）。

诊断口径原则：**可达性不等于可用性，默认值不等于真实值**（见 [[native-loader-start-path-only]]）。


## timeline

- time: 2026-11-10T01:09:47
  kind: decision
  summary: "Created this page: 连接状态与测速必须如实反映上游, 不得用可达性冒充可用性"
  source: git log d2115b1 / c9d224b / 5652b6a
  affects: [connection-state-truthfulness]

- time: 2026-11-10T01:12:21
  kind: decision
  summary: "固化一病三面的成因与修法, 含两次误判纠正"
  source: git log d2115b1 / c9d224b / 5652b6a
  affects: [connection-state-truthfulness]
