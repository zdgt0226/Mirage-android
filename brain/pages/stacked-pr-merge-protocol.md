---
id: stacked-pr-merge-protocol
title: "栈式 PR 用合并提交, 不带 --delete-branch; 非 main 基线无 CI"
category: decision
status: active
tags: [process, ci]
created: "2026-11-10T01:09:47"
updated: "2026-11-10T01:13:01"
---

## compiled_truth

**规程一：栈式 PR 必须用合并提交，不能 squash。** squash 为 main 生成全新提交，子分支不以它为祖先，接着合子 PR 时 git 会把父 PR 的改动在同一批文件上重铺一遍，冲突风险高。统一用 `gh pr merge <n> --merge`，事后用 `git merge-base --is-ancestor` 核对祖先关系。

**规程二：合并栈式 PR 时不要带 `--delete-branch`。** 实际发生过：合 #11 时带了它，GitHub 删掉 base 分支后**没有**把子 PR #12 改指 main，而是直接把 #12 转 `CLOSED`，随即死锁——关闭状态不能改 base，base 分支已删不能 reopen。

**恢复办法（已验证）**：
```bash
git push origin <被合并的提交>:refs/heads/<被删的分支名>
gh pr reopen <子PR编号>
gh pr edit <子PR编号> --base main
```

**正确顺序**：① 所有子 PR 先改指 main（趁它们还 OPEN、base 还存在）；② 自底向上逐个 `--merge`，不带 `--delete-branch`；③ 全部 MERGED 后再统一删远端分支。

**附带事实**：CI 的 `on.pull_request.branches` 与 `on.push.branches` 都只列 `[main, master]`，过滤的是 **base**，所以 base 不是 main 的 PR **不会自动触发 CI**。栈顶分支需手动触发：`gh workflow run ci.yml --ref <栈顶分支>`。

**冲突实例（2026-11-10）**：#33 与 #31 同改 `CoreService.kt`——`CoreService.kt` 自动合干净，真正冲突的是 `scripts/endpoint-allowlist.txt`（#33 与 #34 都往文件尾追加）。解法是**两段都留**，再跑门禁确认 0 命中。


## timeline

- time: 2026-11-10T01:09:47
  kind: decision
  summary: "Created this page: 栈式 PR 用合并提交, 不带 --delete-branch; 非 main 基线无 CI"
  source: "docs/AUDIT_HANDOFF.md §6"
  affects: [stacked-pr-merge-protocol]

- time: 2026-11-10T01:13:01
  kind: decision
  summary: "固化栈式 PR 规程与死锁恢复办法"
  source: "docs/AUDIT_HANDOFF.md §6"
  affects: [stacked-pr-merge-protocol]
