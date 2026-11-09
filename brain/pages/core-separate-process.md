---
id: core-separate-process
title: ":core 跑独立进程, UI 与内核只经 AIDL 通信"
category: decision
status: active
tags: [arch, ipc]
created: "2026-11-10T01:09:35"
updated: "2026-11-10T01:10:14"
---

## compiled_truth

**决定**：VPN 内核跑在独立进程 `:core`（`CoreService : VpnService` + JNI），UI 主进程只通过 AIDL（`IMirageCore` / `IMirageCallback`）控制它，并带 Binder 死亡代理与自动重连。

**为什么**：UI 进程被系统杀掉、崩溃或用户划掉任务卡，都不应断隧道。单进程方案下 UI 的任何 OOM 都会连带杀死代理。

**代价（都已踩过）**：
- 状态必须跨进程同步，回调状态位失配曾导致首页声称「未连接」而隧道在跑。
- `serviceConfig` 这类跨线程共享状态需要显式锁，已改为锁内 copy-on-write（`updateConfig`）。
- 所有原生查询都要考虑「只 bind 未 start」的场景，见 [[native-loader-start-path-only]]。
- 调试要同时看两个进程的 logcat。

**影响面**：任何「在 UI 进程直接调原生库」的想法都是错的——那个进程里 `.so` 根本没加载。


## timeline

- time: 2026-11-10T01:09:35
  kind: decision
  summary: "Created this page: :core 跑独立进程, UI 与内核只经 AIDL 通信"
  source: docs/ARCHITECTURE_V2.md / git log
  affects: [core-separate-process]

- time: 2026-11-10T01:10:14
  kind: decision
  summary: "从 ARCHITECTURE_V2 与提交历史提取 v2 架构决策"
  source: docs/ARCHITECTURE_V2.md
  affects: [core-separate-process]
