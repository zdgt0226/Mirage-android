---
id: device-first-verification
title: "结论以真机实测为准, 结构推断不作结论"
category: decision
status: active
tags: [process, testing]
created: "2026-11-10T01:09:47"
updated: "2026-11-10T01:13:01"
---

## compiled_truth

**采信规则一：结论以真机实测为准。** 默认优先用 `adb devices` 检测到的活动设备（如 `BH905W2A9G` / `SM_S9260`）做全量构建与实机验证。结构推断**不作结论**——至少三次「结构上成立」的断言被真机当场否证。

**采信规则二：测试必须调用生产函数，不得在测试里重写同一份判断逻辑。** 本项目真实出现过四次反例，例如：
```kotlin
// 声称覆盖 GeoManager 的 https 校验，实际在测 Kotlin 标准库
assertTrue(validHttps.startsWith("https://", ignoreCase = true))
```
改坏 `GeoManager` 的校验，这条断言照样绿。逻辑散在 `Context`/IO/原生依赖之间触达不到时，**先抽成纯函数再测**（范例：`CoreService.StateMachine`、`tun/tcp.rs` 的 `may_query_upstream`）。

**采信规则三：新增或改测试后做一次变异验证**——把被测逻辑改坏，确认对应用例变红；不变红就是没有防护力的用例。

**截图纪律（硬规矩）**：截屏前必须确认应用在前台（`mWakefulness=Awake` 且 `topResumedActivity`/`mResumedActivity` 为 `com.mirage.android`）——曾误抓到用户的私人微信界面。另：**永不 `pm clear` / `adb uninstall`**；`wm size` / `wm density` 覆盖用完必须还原。

与 [[feedback-evaluation-rule]] 配套：一条反馈先过独立评估，再过真机。


## timeline

- time: 2026-11-10T01:09:47
  kind: decision
  summary: "Created this page: 结论以真机实测为准, 结构推断不作结论"
  source: "AGENTS.md §2 / docs/AUDIT_HANDOFF.md §5.1"
  affects: [device-first-verification]

- time: 2026-11-10T01:13:01
  kind: decision
  summary: "固化真机优先与测试有效性两条采信规则"
  source: "AGENTS.md §2 / docs/AUDIT_HANDOFF.md §5.1"
  affects: [device-first-verification]
