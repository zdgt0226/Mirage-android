---
id: container-only-build
title: "Kotlin/APK 必须在 systemd-nspawn 容器内构建"
category: decision
status: active
tags: [build, env]
created: "2026-11-10T01:09:35"
updated: "2026-11-10T01:11:28"
---

## compiled_truth

**宿主机没有 gradle、没有 wrapper、`ANDROID_HOME` 为空。** 工具链只在 systemd-nspawn 容器 `/var/lib/machines/android-builder`（Gradle 8.9 + JDK 17）里。

入口脚本：

```bash
bash scripts/build-android.sh                    # debug
VARIANT=release bash scripts/build-android.sh    # release
bash scripts/build-android.sh native             # 只编 Rust 原生库
```

只做 Kotlin 语法校验时直接 `systemd-nspawn --as-pid2` 挂 `android` / `.build/gradle-home` / `/opt/android-sdk`，跑 `./gradlew :app:compileDebugKotlin --no-daemon`；换成 `assembleDebug` / `:app:testDebugUnitTest` 同理（完整命令在 `docs/AUDIT_HANDOFF.md` 第 0 节）。

**规则：Kotlin 改动未经容器编译不得提交。** 教训来自第 0 批——`android { }` 块内 `java` 绑到 `JavaPluginExtension`，`java.util.Properties()` 解析不到，提交后**每一次 gradle 构建都失败**。

**第二条教训（新 worktree）**：`android/app/src/main/jniLibs/` 被 `.gitignore` 忽略，新 worktree 里**没有** `libmirage_jni.so`。打 APK 前必须先构建原生库，安装前用 `unzip -l <apk> | grep lib/` 核对含 `lib/arm64-v8a/libmirage_jni.so`——2026-09-26 曾因此打出无内核的 release 包。

配套门禁见 [[jni-symbol-gate]]。


## timeline

- time: 2026-11-10T01:09:35
  kind: decision
  summary: "Created this page: Kotlin/APK 必须在 systemd-nspawn 容器内构建"
  source: AGENTS.md / docs/AUDIT_HANDOFF.md
  affects: [container-only-build]

- time: 2026-11-10T01:11:28
  kind: decision
  summary: "固化容器构建约束与两条血的教训"
  source: "docs/AUDIT_HANDOFF.md §0"
  affects: [container-only-build]
