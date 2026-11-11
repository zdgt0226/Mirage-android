---
id: core-owned-state-via-aidl
title: ":core 进程写的状态只能经 AIDL 读, UI 进程不直读同名 SharedPreferences"
category: decision
status: active
tags: [aidl, process, sharedpreferences]
created: "2026-11-11T09:01:43"
updated: "2026-11-11T09:01:43"
---

## compiled_truth

`:core` 进程写入的持久化状态，UI 进程**不得**直接打开同名 SharedPreferences 去读，必须经 `ICoreService` 由 `:core` 自己读出来返回。

**原因**：SharedPreferences 是进程内缓存。`CoreService` 跑在 `:core`（见 [[core-separate-process]]），`TrafficStatsStore` 的账本由它每 30 秒落盘一次；UI 进程 `getSharedPreferences("mirage_traffic")` 拿到的是它自己首次加载时的快照，此后 `:core` 的写入一律看不到，直到 UI 进程重启。旧版 `HomeFragment` 直接调 `TrafficStatsStore.getToday(ctx)` 就踩着这个坑，只是数字「看起来合理」所以没人发现。`CoreController.unbind` 的注释里记过同一个根因（跨进程配置陈旧）。

**落地**：`ICoreService.getUsageTotals()` 返回 `[todayUp, todayDown, monthUp, monthDown]`，`CoreService.usageTotalsInternal()` 读本进程的 `TrafficStatsStore`。UI 侧读不到（未绑定 / Binder 异常）时保留上一次读数，不覆盖成 0。

**刻意不补「未落盘增量」**：用量因此比实时累计滞后至多 30 秒。补增量需要调 `MirageNative.getStats`，而它内部的 `monitor::sample` 有副作用——距上一个样本不足 800ms 的调用方会拿到速率 0。面板每 10 秒读一次用量，若挤掉速率推送的采样窗口，首页速率会周期性闪零。同理，任何新代码都不应为了「顺手拿个累计值」去调 `getStats`。

**同批的另外两条约定**：

- 面板展开时 `setMonitorActive(true)` 只应带起**有读者**的拉取。连接列表有了读者（面板「连接」视图）；`recentLogs()` 没有读者，已从该分支去掉，`_logs` 仅靠 `onLog` 回调增量维护。
- 判断一条数据流是否在空转，要看有没有人 `collect`，不是看 ViewModel 有没有暴露它，见 [[dead-code-reference-nature]]。

实机取证方式见 [[device-first-verification]]。


## timeline

- time: 2026-11-11T09:01:43
  kind: decision
  summary: "Created this page: :core 进程写的状态只能经 AIDL 读, UI 进程不直读同名 SharedPreferences"
  source: "2026-11-11 面板用量与连接列表"
  affects: [core-owned-state-via-aidl]

- time: 2026-11-11T09:01:43
  kind: decision
  summary: "补今日/本月用量时确立"
  source: "feat/panel-connections-usage 实机验证"
  affects: [core-owned-state-via-aidl]
