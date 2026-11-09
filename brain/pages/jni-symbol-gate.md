---
id: jni-symbol-gate
title: "JNI 符号集 fail-closed 门禁: 自定义内核缺符号即拒装"
category: decision
status: active
tags: [ci, jni]
created: "2026-11-10T01:09:47"
updated: "2026-11-10T01:11:28"
---

## compiled_truth

**决定**：`CoreManager.importCore` 与 `NativeLoader.load` 都调 `NativeSymbolPolicy.verify()`，比对 `.so` 的 `.dynsym` 导出与 `MirageNative` 反射出的 native 方法清单，**缺符号即拒绝；解析不出符号表同样拒绝（fail-closed）**。

**为什么需要**：原先的 `ElfChecker` 只读 ELF 头的 `e_machine`。架构对但接口面偏旧的 `.so` 能 `System.load` 成功，之后每次调缺失方法抛 `UnsatisfiedLinkError`，被 `NativeGuard` 兜住返回默认值——**静默降级**，整段功能失效却不崩溃，最难查。

**实测依据（2026-11-03）**：上游 Mirage-rs run `41558057174`（`62cf686`, v0.17.3）的 `mirage-jni-so` 导出 43 个 JNI 符号，本仓 `MirageNative` 声明 46 个，缺 `setNodeList` / `setFailoverPolicy` / `onNetworkChanged`。该 run 启动于 `01:11:18Z`，早于 PR #22 合并（`01:36:47Z`）25 分钟——说明「上游新版本 ≠ 接口面更全」。

**脆弱点**：门禁依赖 `-keep class com.mirage.android.core.MirageNative { *; }`。收窄这条 ProGuard 规则会让它拒绝**一切**自定义内核（方向安全，但会挡掉合法内核）。CI 另有一条 JNI 存活门禁，`onFailoverEvent` 已纳入。


## timeline

- time: 2026-11-10T01:09:47
  kind: decision
  summary: "Created this page: JNI 符号集 fail-closed 门禁: 自定义内核缺符号即拒装"
  source: git log aef9db4 / 5b6f925
  affects: [jni-symbol-gate]

- time: 2026-11-10T01:11:28
  kind: decision
  summary: "固化符号集门禁的动机与实测依据"
  source: "docs/AUDIT_HANDOFF.md §0 / git log aef9db4"
  affects: [jni-symbol-gate]
