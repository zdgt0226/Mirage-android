---
id: gesture-shell-navigation
title: "导航改全手势三页外壳, Compose 渐进迁移"
category: decision
status: active
tags: [ui]
created: "2026-11-10T01:09:35"
updated: "2026-11-11T18:45:47"
---

## compiled_truth

**决定**：参照 Nokia N9 的全手势逻辑重做导航——三页 `HorizontalPager` 外壳，**首页只留连接开关与节点下拉**；首页上滑调出详细面板（速率 / 延迟 / 连接列表 / 流量统计），右滑进节点管理，左滑进详细设置。底部 Tab 栏与 `ViewPager2` 退役。

**迁移策略：Compose 外壳 + 遗留 View 内容共存**，不做一次性重写。旧 View 岛（含 `activity_main.xml`、约 1116 行死代码）按 S4/S5 阶段分批删。

**动效规范（可迁移的物理原则，不机械复刻 iOS）**：
- 弹簧参数由 Apple 的 `response` 换算而来：mass=1 时 `ω = sqrt(stiffness)`、`response = 2π/ω`，故 `stiffness = (2π/response)²`。0.4s → 246.7，0.3s → 438.6。**Compose 自带的 `Spring.Stiffness*` 常量（1500/400/200）都不对号**，换算函数是 `ShellMotion.stiffnessForResponse`，有单测。
- **禁止手写动量投射与速度接力**：框架已提供（`PagerDefaults.flingBehavior`、`AnchoredDraggableState.settle`），自造吸附引擎属方案明文拒绝项。

**真机踩出来的三条**：
- 页面指示点会压住面板把手——折叠位偏移必须扣掉把手 + 指示点 + **导航栏 inset**（`WindowInsets.navigationBars`）；
- 展开面板必须接 `BackHandler`，否则返回键直接退出应用；
- `Connecting` 态**不得禁用**开关（旧 `HomeFragment` 此处 `isEnabled = true`），禁用是回归。
- 子页打开时要锁住外壳横向手势（`shellNavEnabled` 关 `userScrollEnabled`、隐指示点、停外壳的 `PredictiveBackHandler`）。

M3 1.3 的菜单读 `surfaceContainer*` 而非 `surface`，主题里这批 token 必须补全，否则下拉菜单背景透明。


## timeline

- time: 2026-11-10T01:09:35
  kind: decision
  summary: "Created this page: 导航改全手势三页外壳, Compose 渐进迁移"
  source: docs/UI_REDESIGN_PROPOSAL.md / git log
  affects: [gesture-shell-navigation]

- time: 2026-11-10T01:11:07
  kind: decision
  summary: "固化全手势外壳与 Compose 迁移策略"
  source: docs/UI_REDESIGN_PROPOSAL.md / git log
  affects: [gesture-shell-navigation]

- time: 2026-11-11T18:45:47
  kind: decision
  summary: "面板与列表的嵌套滚动只把手指拖动 (NestedScrollSource.UserInput) 的位移转交给面板, 惯性位移不转交; onPostFling 对不在档位上的面板一律 settle。否则惯性撞顶后面板会停在两个档位之间"
  source: "fix/panel-nested-scroll-settle, 设备 BH905W2A9G 手势回归"
  affects: [ui/shell/DetailPanelContent.kt]
