---
id: network-policy-offload
title: "网络策略下沉内核: 判定可下沉, 建 TUN fd 不可"
category: decision
status: active
tags: [arch, offload]
created: "2026-11-10T01:09:35"
updated: "2026-11-10T01:11:07"
---

## compiled_truth

**决定（2026-11-03 方案，P1–P3 已落地）**：网络策略判定全部下沉到 `mirage-core`，Kotlin 外壳退回「建 fd + 执行」的角色。

**硬边界，不要试图绕开**：
- **可完全下沉**：健康判定、节点选优、热切换（`setNode` 不需要新 fd）。
- **不可下沉**：涉及重建 TUN 的重启。内核拿不到 TUN fd——`VpnService.Builder.establish()` 是框架 API，需要应用上下文与 VPN 权限，Rust 侧无法自建接口。内核只能发出「请求重启」事件，由壳决定复用 fd 还是重建。
- 「TUN 直连内核」在 Android 上**无法按字面实现**，可达上限就是「应用建 fd、移交内核」。

**阶段结果**：P1 接口面（唯一改 JNI 的一段，`onFailoverEvent` 已纳入 JNI 存活门禁）→ P2 内核实现故障切换看门狗（默认关）→ P3 拆除外壳侧看门狗，判定与选路全部由内核负责。

**踩过的坑**：P3 之后 `isHealthy()` 虽在内核算出并经 AIDL 导出，但**没有任何 Kotlin 消费者**，于是「连接成功」的提示与上游真实状态脱钩。教训见 [[connection-state-truthfulness]]。下沉过程中看门狗持有 runtime 引用引发的 panic 见 [[tokio-runtime-drop-panic]]。


## timeline

- time: 2026-11-10T01:09:35
  kind: decision
  summary: "Created this page: 网络策略下沉内核: 判定可下沉, 建 TUN fd 不可"
  source: docs/CORE_OFFLOAD_PLAN.md
  affects: [network-policy-offload]

- time: 2026-11-10T01:11:07
  kind: decision
  summary: "固化下沉硬边界与 P1-P3 结果"
  source: docs/CORE_OFFLOAD_PLAN.md
  affects: [network-policy-offload]
