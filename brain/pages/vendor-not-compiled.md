---
id: vendor-not-compiled
title: "vendor/ 只是参考副本, 不参与编译"
category: concept
status: active
tags: [protocol, gotcha]
created: "2026-11-10T01:09:35"
updated: "2026-11-10T01:10:15"
---

## compiled_truth

`native/mirage-core/src/vendor/` 是**上游源码的参考副本**，用于比对，**不参与编译**。`SYNC.md` 原话：其中记录的 commit 哈希「只代表参考副本的版本，不代表本 APK 的协议行为」。

**由此产生的真实误导**：`MirageNative.version()` 会显示 `vendored 62cf686 (v0.17.3)`，而实际编译进去的协议是 v0.15 时代的（见 [[protocol-break-v015]]）。读这个字符串判断协议行为会得出错误结论。

**规则**：
- 判断协议行为只看 `native/mirage-core/src/` 下**参与编译**的模块；
- 同步 vendor 副本是独立动作，不等于升级协议；
- 对照上游实现时以本地可读源码为准（`/opt/reference/meow-android`），不凭记忆断言；`sing-box` 无本地副本，不得虚构其文件路径。


## timeline

- time: 2026-11-10T01:09:35
  kind: decision
  summary: "Created this page: vendor/ 只是参考副本, 不参与编译"
  source: native/mirage-core/src/vendor/SYNC.md
  affects: [vendor-not-compiled]

- time: 2026-11-10T01:10:15
  kind: decision
  summary: "固化 vendor/ 不参与编译这一高频误判点"
  source: native/mirage-core/src/vendor/SYNC.md
  affects: [vendor-not-compiled]
