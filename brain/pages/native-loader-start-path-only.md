---
id: native-loader-start-path-only
title: "NativeLoader 只在启动路径加载, 查询路径必须有安全默认值"
category: decision
status: active
tags: [jni, ipc]
created: "2026-11-10T01:09:47"
updated: "2026-11-10T01:11:55"
---

## compiled_truth

**原则（守卫第 3 条）**：**绝不在查询/设置等非启动路径隐式触发 `NativeLoader.load()`。** 加载只发生在 `CoreService.onStartCommand` 里。

**推论（极易误判）**：当 `:core` 只被 bind 而没被 start 时，**任何原生支撑的查询都返回安全默认值**，不是真实值。`isHealthy()`、延迟、池大小这类读数在未启动态下一律无意义。所有 Binder 原生调用都有 `NativeGuard` 兜底。

**为什么不放开**：隐式加载会把 `.so` 拉进本不该持有它的进程/路径，并让一次无害的 UI 查询变成有副作用的初始化。

**配套坑**：安全默认值必须选对方向。`clearNativeLogs()` 原先失败返回 `true`（谎报成功），已改为 `?: false`。池大小的默认值同样修正过——默认值错了比报错更难查。

与之呼应的失效模式见 [[jni-symbol-gate]]（静默降级）与 [[connection-state-truthfulness]]（默认值被当成真实状态展示）。


## timeline

- time: 2026-11-10T01:09:47
  kind: decision
  summary: "Created this page: NativeLoader 只在启动路径加载, 查询路径必须有安全默认值"
  source: git log ddbfd20 / f2ce1c8
  affects: [native-loader-start-path-only]

- time: 2026-11-10T01:11:55
  kind: decision
  summary: "固化守卫原则与其对查询语义的影响"
  source: git log ddbfd20 / f2ce1c8
  affects: [native-loader-start-path-only]
