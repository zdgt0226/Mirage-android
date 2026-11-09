---
slug: roadmap
title: Roadmap
role: milestones
updated: "2026-11-10T01:09:20"
---

# Roadmap

进度以 git 历史与 `docs/` 中的实测记录为准，日期取自实际提交日。**未来项无排期**——仓库里不存在日期承诺，不替用户编。

```mermaid
gantt
  title 已完成阶段
  dateFormat YYYY-MM-DD
  axisFormat %m-%d
  section 审计
  第 0–4 批全量闭环 + 多模型复审返工 :done, a1, 2026-09-16, 2026-09-22
  section 工具链
  compileSdk/targetSdk 36 + minSdk 门禁 :done, a2, 2026-09-21, 2d
  section 安全与协议
  移除硬编码服务器 + 端点防泄露门禁 :done, a3, 2026-09-29, 1d
  内核协议同步至 v0.15.0 (协议断代) :done, a4, 2026-09-29, 1d
  section 下沉
  下沉方案 + P1 接口面 + P2 内核实现 :done, a5, 2026-11-03, 2d
  P3 外壳看门狗拆除 :done, a6, 2026-11-05, 1d
  参考副本同步至 v0.17.3 :done, a7, 2026-11-03, 1d
  section UI
  D1 流体基座 / D2 全手势外壳 :done, a8, 2026-11-06, 4d
  section 修复
  连接状态如实化 :done, a9, 2026-11-09, 1d
  四连合 (CI 门禁/panic/回调/路由泄漏) :done, a10, 2026-11-10, 1d
```

## 在途（PR 已开，未合）

| PR | 内容 | 阻塞点 |
|---|---|---|
| #30 | UI S2 — 上滑面板吞吐曲线、请求流列表、嵌套滚动契约 | 与 #32 同改 `MainActivity.kt`，互相冲突 |
| #32 | UI S3 — 分流规则页 Compose 化 | **有明确未复验项**：`shellNavEnabled` 导航锁、拖拽重排、CRUD、应用按钮只做了渲染验证 |

两者必须定序合并，且 #32 合前需接真机回归，见 [[stacked-pr-merge-protocol]]。

## 计划（无排期，按价值排序）

1. **UI S4 / S5** — 设置子面板吸收 `SettingsActivity`；删 `ViewPager2` 依赖、`activity_main.xml` 与遗留 View 岛。方案在 `docs/UI_REDESIGN_PROPOSAL.md`。
2. **恢复今日 / 本月用量显示** — `TrafficStatsStore` 每 30s 在写，唯一读者随旧 `HomeFragment` 一起死了。改动落在 #30 内部。
3. **targetSdk 37 准备** — `ACCESS_LOCAL_NETWORK` 权限、导入 `.so` 需 `setReadOnly()`、CT 默认开启、NDK 升级以拿 `android_getnetworkblockedreason`。
4. **清理技术债** — 5 个死 AIDL 方法、错误码 `-1`/`-5` 合并多种成因、`MirageNative.version()` 报参考副本版本而非编译版本（见 [[vendor-not-compiled]]）。
5. **CI 覆盖缺口** — `pull_request.branches` 只覆盖 `main`/`master`（非 main 基线 PR 无门禁）；`clippy --lib` 从不 lint 测试代码；`native/mirage-core/` 约 90 条 CJK 字面量未纳入扫描。
6. **`tls_padding` 可选开关** — 优先级低：收端恒剥零、上游默认也关，属缺一个 opt-in，不是互通或指纹问题。
7. **iOS 移植** — `mirage-core` 已为此保持平台无关，但是否真做**未确认**，见 `background` 的低置信段。
