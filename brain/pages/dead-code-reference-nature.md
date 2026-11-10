---
id: dead-code-reference-nature
title: "判死代码看引用性质, 不看引用计数"
category: decision
status: active
tags: [dead-code, kotlin, android]
created: "2026-11-10T23:53:48"
updated: "2026-11-10T23:54:09"
---

## compiled_truth

判一个符号是不是死代码，**grep 出来的引用数不作数，必须逐行打开看引用的性质**。

#40 清理首页/节点页 View 孤岛时，初查引用计数全都不为零：

- `HomeFragment` 外部引用 2
- `NodesFragment` 外部引用 5
- `NodeAdapter` 外部引用 5

按计数判，三个都活着。逐行打开才发现**全部是注释**——Compose 版在自己的文件里写「行为逐项对齐被替换的 `NodesFragment`」「被替换的 `NodeAdapter:95` 的交互」这类设计依据说明。这类注释在一个迁移中的代码库里很常见：新实现引用旧实现的名字来解释自己为什么这么写。

**本仓库可用的四条佐证信号**（任一不成立就要重新怀疑结论）：

1. **宿主存在性**。Fragment 必须有东西托管它。全仓 `grep` 若没有 `ViewPager2`、`FragmentStateAdapter`、`supportFragmentManager`，那么所有 Fragment 都不可达，与它们各自被提及多少次无关。
2. **Binding 类**。启用 viewBinding/dataBinding 后，一个布局活着的标志是有人用它的 `XxxBinding`。`activity_main.xml` 判死就是靠这条：`MainActivity` 走 `setContent`，全仓没有 `ActivityMainBinding`，也没有 `R.layout.activity_main`。
3. **顶层声明数**。文件里若只有一个顶层类，删它不会连带带走别处在用的常量或扩展函数。多个顶层声明的文件要逐个查。
4. **设计期属性**。`tools:listitem="@layout/item_node"` 这类只在 IDE 预览生效，不构成运行时引用；但要确认引用它的布局本身是不是也在删除清单里。

**反向约束**：manifest 注册的组件、JNI `Java_*` 导出、AIDL 桩、ProGuard `-keep`、`@Preview` 一律不能靠静态图判死，见 [[jni-symbol-gate]]。`SettingsActivity` 就是这样被保下来的——它在 manifest 里注册着，唯一代码引用来自本次被删的 `HomeFragment`，删完会变成「注册但不可达」，但处置它属于 S4 下沉方案的范围，不在删除 PR 里顺手做。

**遗留的处理方式**：删完之后，Compose 侧那几条指向已删文件的注释**刻意保留**。它们记录的是现有实现为什么这么写，git 历史里被引用的实现仍可查；在一个删除 PR 里顺手改语义注释，收益小而丢失原意的风险大。

配套做法见 [[container-only-build]]（删完必须进容器编译验证）与 [[device-first-verification]]。


## timeline

- time: 2026-11-10T23:53:48
  kind: decision
  summary: "Created this page: 判死代码看引用性质, 不看引用计数"
  source: "2026-11-10 View 孤岛清理 (#39 / #40)"
  affects: [dead-code-reference-nature]

- time: 2026-11-10T23:54:09
  kind: decision
  summary: "从 #39/#40 两刀 View 孤岛清理中沉淀"
  source: "#39 / #40 实际判定过程"
  affects: [dead-code-reference-nature]
