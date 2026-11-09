---
id: tokio-runtime-drop-panic
title: "在 runtime 自己的工作线程上 drop 最后一个 Arc<Runtime> 会 panic"
category: concept
status: active
tags: [rust, concurrency, gotcha]
created: "2026-11-10T01:09:47"
updated: "2026-11-10T01:12:21"
---

## compiled_truth

**机制**：在某个 tokio runtime **自己的工作线程**上 drop 掉该 runtime 的最后一个 `Arc<Runtime>`，会 panic（`shutdown.rs:51:21`）。`Handle` **不**保持 runtime 存活，所以持 `Handle` 是安全的、持 `Arc<Runtime>` 不是。

**本仓的两个触发点**（都已修）：
1. 任务里捕获了整个 `Arc<RunState>`（内含 `rt`）——改成 `let handle_for_task = state.rt.handle().clone()`；
2. **故障切换看门狗每轮迭代克隆 `Arc<RunState>`**——这是真正的最后持有者。改成只克隆 `Arc<TunStack>`：

```rust
let Some(stack) = RUNTIME.lock().unwrap_or_else(|e| e.into_inner())
    .as_ref().map(|s| s.stack.clone())
```

并把签名改为 `switch_engine_to(stack: &Arc<TunStack>, uri: &str)`。

**排查教训（两条，都比机制本身值钱）**：
- 第一次修完我**用结构推断断言「机制已移除」**，真机测试当场否证。此类问题只有真机能定性，见 [[device-first-verification]]。
- 统计 panic 次数时，反复 `cat` 同一日志会把同一时间戳算成两次——必须去重计数。

**实测结果**：修后 22 次重启 0 panic（修前 8 次里 2 次 panic）。


## timeline

- time: 2026-11-10T01:09:47
  kind: decision
  summary: "Created this page: 在 runtime 自己的工作线程上 drop 最后一个 Arc<Runtime> 会 panic"
  source: "真机实测 / git log 5652b6a"
  affects: [tokio-runtime-drop-panic]

- time: 2026-11-10T01:12:21
  kind: decision
  summary: "固化 runtime drop panic 的真实触发点与排查教训"
  source: "真机实测 / git log 5652b6a"
  affects: [tokio-runtime-drop-panic]
