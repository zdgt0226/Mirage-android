# Android 客户端审计 — 交接文档

> 面向接手本项目的协作者与 AI Agent。
> 配套可视化路线图：<https://claude.ai/artifact/FaiBQfWKqSji1sFDVzHtsA>
>
> **审计基线** `fd6cd3e` · **已完成** 第 0、1、2 批（`cade5d7`、`3aeb445`、`fbf26f1`）及原生 60ms 预读（`f3274b1`）· **未完成** 第 3、4 批
>
> 本文所有 `file:line` 基于 `fbf26f1`。引用旧行号的历史记录已失效，以本文为准。

---

## 0. 先读这一节：工作环境

### 构建必须在容器内进行

宿主机**没有** gradle、没有 gradle wrapper、`ANDROID_HOME` 为空。
完整工具链在 systemd-nspawn 容器 `/var/lib/machines/android-builder` 内（Gradle 8.9 + JDK 17）。

```bash
# 完整构建（推荐入口）
bash scripts/build-android.sh              # debug
VARIANT=release bash scripts/build-android.sh   # release

# 只编 Rust 原生库（改了 native/ 之后必须跑，jniLibs 是 gitignore 的本地产物）
bash scripts/build-android.sh native
```

**只做 Kotlin 语法校验**（比全量构建快很多，改 Kotlin 后先跑这个）：

```bash
cd /opt/Mirage-android
systemd-nspawn -D /var/lib/machines/android-builder --as-pid2 -q \
  --bind="$PWD/android:/workspace" \
  --bind="$PWD/.build/gradle-home:/root/.gradle" \
  --bind="/opt/android-sdk:/android-sdk" \
  /bin/bash -c '
    export JAVA_HOME=/opt/jdk-17 ANDROID_HOME=/android-sdk
    export ANDROID_NDK_HOME=/android-sdk/ndk/26.3.11579264
    export GRADLE_USER_HOME=/root/.gradle GRADLE_OPTS="-Dorg.gradle.native=false"
    export PATH=/opt/jdk-17/bin:/opt/gradle-8.9/bin:$PATH
    cd /workspace && gradle :app:compileDebugKotlin --no-daemon -q
  '
```

把 `compileDebugKotlin` 换成 `assembleDebug assembleRelease` 或 `:app:testDebugUnitTest` 即可。

> ⚠️ **教训**：第 0 批曾因未在容器内验证而提交了一个让**每一次 gradle 构建都失败**的错误
> （`android { }` 块内 `java` 绑定到 `JavaPluginExtension`，`java.util.Properties()` 解析不到）。
> Kotlin 改动一律先过容器编译再提交。

### Rust 侧

宿主机可直接构建，android 交叉编译目标已安装：

```bash
cd native/mirage-core && cargo test --lib && cargo build --release
```

### 真机

`adb devices` 通常有设备在线（如 `R5CX21FD9PX`）。AGENTS.md 要求真机验证优先。

---

## 1. 已完成

### 第 0 批 — `cade5d7`（四项独立暴露面，无架构改动）

| # | 问题 | 处置 | 验证方式 |
| :-- | :--- | :--- | :--- |
| 1 | `debug_server` 无鉴权且随每个构建发布。Android 不隔离 App 间 loopback，任意持 `INTERNET` 权限的应用可读 `/debug/dns`、`/debug/conns`，并 `POST /debug/control` 执行 `close_all_conns` | 模块与启动点同置于 `cfg(any(debug_assertions, feature = "debug-server"))`（`lib.rs` / `tun/mod.rs`），新增该 feature（默认关） | 重编后 `.so` 中 `DEBUG-SERVER\|/debug/control\|close_all_conns` 命中 **0**；debug rlib 命中 12 |
| 2 | `mirage://` 深链静默导入**并自动选中**节点。MainActivity 为 `exported` + `BROWSABLE`，网页链接即可构成完整中间人 | 结构化校验（host 非空、端口 1–65535、长度 ≤2048）+ 确认框展示 host/port/SNI + **永不自动选中** + 清空 `intent.data` | 人工代码审查 |
| 3 | 唯一构建路径产出 `debuggable`、AOSP 调试密钥签名却命名为发布版的 APK | 新增来自未入库 `keystore.properties` / `MIRAGE_KEYSTORE_*` 的 release 签名配置（缺失时产出未签名包并告警，**不回落调试密钥**）；开启 R8 + 资源裁剪；补齐 JNI keep 规则；`VARIANT=release`；产物名携带变体 | 见下方「R8 keep 规则验证」 |
| 4 | `clearActive()` 无身份检查，旧实例异步销毁时会抹掉新实例，导致 `protectFd` 找不到实例 → 隧道 socket 不受保护 → 自环 | 改为同一性比较 `clearActive(this)` | 人工代码审查 |

同批还向 `.gitignore` 加入 `android/keystore.properties`、`*.jks`、`*.keystore`。

### 第 1 批 — `3aeb445`（状态机，照搬 meow `BaseService` 的五个模式）

| 模式 | 消除的问题 | 实现位置 |
| :--- | :--- | :--- |
| `ServiceState` 枚举，`Stopping` 在锁内、拆除**之前**置位 | 用户点断开后 VPN 0–3 秒自己回来 | `CoreService.kt:56`、`:240`、`:724` |
| `cancelPeriodicJobs()` 置于 `startInternal` 开头 | failover 每次重启叠加一套周期 job，N 个 watchdog 各自打全节点测速 | `CoreService.kt:114`、`:253` |
| `startLocked()` + `catch (Throwable)` + `failAndStop()` | 启动失败仍显示「已连接」，rc=−2（授权被撤销）时流量全明文 | `CoreService.kt:190`、`:255`、`:281` |
| `stopInternal` 补 `stopSelf()` | `:core` 进程与已加载原生库永不释放 | `CoreService.kt:747` |
| `RemoteCallbackList` + 同步 begin/finish | UI 进程死后死 binder 永久累积，每条日志一次注定失败的事务 | `CoreService.kt:69`、`:213`、`:942` |

附带修复：通知文案启动期为「正在连接…」，成功后才改「已连接」；`NativeLoader.load()` 返回值检查；
`notifyState()` 的 `isRunning()` 加 `runCatching`（原生库加载失败时它抛 `Error`）。

**未采用 `cancelAndJoin`**：状态守卫已堵住竞态，而 `stopInternal` 跑在 binder 线程上，
`runBlocking` join 会把调用方一并阻塞。这是有意的偏离，不是遗漏。

#### 第 2 批 — `fbf26f1`（网络层：消除明文泄漏窗口与底层网络自相覆盖）

| # | 问题 | 处置 | 验证方式 |
| :-- | :--- | :--- | :--- |
| 1 | failover 拆掉 TUN 描述符（`tunFd?.close()`）并 `delay(3000)`，导致 3 秒内全局流量经物理网卡明文外泄 | 重连与 failover 不关 fd，`startLocked` 复用存活 `tunFd`，仅在真正断开时释放；failover 路径返回 `false` 激活退避；watchdog 首行检测物理网络离线直接跳过 | 实机 Sony SO-02K (Android 9) 验证：VPN 接口 `tun0` 保持存活，无明文外泄 |
| 2 | `onCapabilitiesChanged` 在蜂窝信号/带宽变化时将 `setUnderlyingNetworks` 误写为蜂窝，与 Rust `ACTIVE_NET_HANDLE` 背离 | 全面迁移为 `registerDefaultNetworkCallback`（API 24+），单一原子入口 `switchTo(n: Network?)` 严格同步状态，移除已废弃的 `cm.allNetworks` 扫描 | 编译 warning 归零；实机 `dumpsys connectivity` 验证 UnderlyingNetwork 严格绑定 `WIFI (209)` |

### 第 0/1/2 批验证结果（容器内实测与实机）

```
compileDebugKotlin     clean（0 警告，已消除全部 allNetworks 弃用警告）
assembleDebug          13.7 MB
assembleRelease         9.4 MB   R8 + 资源裁剪，−31%
testDebugUnitTest      BUILD SUCCESSFUL
cargo test --lib       131 passed, 0 failed
实机验证                Sony SO-02K (Android 9) / SM-S9260 实机通过，Google/Baidu 双向正常，断连无幽灵重启
```

**R8 keep 规则验证**（拆 release dex，确认 JNI 边界未被混淆打断）：

```bash
unzip -q -o app-release-unsigned.apk 'classes*.dex'
/opt/android-sdk/build-tools/34.0.0/dexdump -d classes.dex > all.txt
grep -c "protectFd" all.txt              # 期望 > 0
grep -oE "MirageNative;\.[a-zA-Z]+" all.txt | sort -u   # 方法名应保持原样
```

实测：`MirageNative` 211 处、`protectFd` 4 处、`resolveConnectionOwner` 2 处，native 方法全部保名。
原生库侧 `Java_com_mirage_*` 导出符号 43 个，完整。

---

## 2. 未完成

### 第 3 批 — 配置与数据

#### 3.1 跨进程配置不一致（改了设置不生效，且无提示）

`:core` 是独立进程（`AndroidManifest.xml` 的 `android:process=":core"`），
而 `SharedPreferencesImpl` 按 (文件, 进程) 缓存，跨进程写不会让对方缓存失效。
`CoreController` 用 `BIND_AUTO_CREATE` 绑定且 `unbind` 只在实际不会被调用的 `destroy()` 里，
所以 `:core` 与 UI 进程同寿，其缓存**永不刷新**。

在 `:core` 内读、但由 UI 进程写的配置：

| 位置 | 配置 | 症状 |
| :--- | :--- | :--- |
| `CoreService.kt:300` | `TunConfigStore.isBypassLanEnabled` | 改了绕过局域网，停止再启动仍是旧值 |
| `CoreService.kt:312` | `TunConfigStore.isIpv6Enabled` | 同上 |
| `CoreService.kt:323` | `TunConfigStore.getMtu` | 同上 |
| `CoreService.kt:333` | `AppFilterStore.getConfig` | 分应用名单改动不生效 |
| `CoreService.kt:423` | `mirage_dns_prefs` | DNS 改动不生效 |
| `CoreService.kt:429` | `mirage_vpn_prefs` block_quic | 同上 |
| `CoreService.kt:621/635/667` | `SettingsStore` 自动重连/检查间隔/failover 模式 | **关掉自动重连不生效** |
| `CoreService.kt:700` | `NodeStore.setSelected`（`:core` 写，UI 也写） | 双向读改写整个 JSON blob，最后写入者覆盖，failover 后两边节点选择不一致 |

**修法**：停止把 SharedPreferences 当 IPC 通道。
`ICoreService.aidl` 已有 `setDnsServers` / `setBlockQuic` / `setOutboundMode` 的正确范式，
照此补 `setBypassLan` / `setIpv6` / `setMtu` / `setAppFilterConfig` / `setAutoReconnect` /
`setCheckInterval` / `setFailoverMode`，由 `:core` 在内存中持有；
或在 `start()` 时把完整 TUN 配置作为 AIDL/Intent 参数传入（`uri`/`pool_size` 已是这个模式）。
节点选择改为只经 `ICoreCallback.onNodeChanged` 单向回流，删掉 `:core` 侧的 `NodeStore.setSelected`。

#### 3.2 `mirage_routing_prefs` 双进程写（埋雷，非现患）

`VpnRepository.kt:271` 与 `CoreService.kt`（binder `setOutboundMode`）都写同一文件。
`apply()` 落盘的是整份进程内缓存快照，两个独立缓存互相覆盖。
目前该文件只有 `outbound_mode` 一个键，丢失更新无从发生——但下一个往里加键的人会中招。
**修法**：删掉 `:core` 侧那次写，只留 `MirageNative.setOutboundMode`，持久化归 UI 进程。

#### 3.3 Geo 替换不原子，且失败被报成成功

`GeoManager.kt:484-490`：

```kotlin
siteFile.delete()          // ← 此刻起 geosite.dat 不存在
siteTmp.renameTo(siteFile) // ← 返回值从不检查
```

随后 `:510` 无条件 `success = true`。进程在 delete 与 rename 之间死亡 → 文件彻底丢失，
静默退化为无 Geo 路由；`renameTo()` 在部分 OEM 存储上静默返回 false 时，UI 仍显示「更新成功」。

**修法**：`Files.move(tmp.toPath(), dest.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)`
一步替换（POSIX `rename()` 原子且覆盖），并把失败传回 `GeoUpdateResult.success`。

#### 3.4 Geo OTA 零完整性校验

唯一门槛是 `dest.length() > 50 * 1024`，之后直接把下载来的二进制喂给 `:core` 进程内的 Rust 解析器。
自定义镜像 URL 无 scheme 白名单，`ConfigBackup.import()` 还会无校验导入 `geosite_url` / `geoip_url`。

**修法**：下载 `.sha256sum` 并在原子替换前校验；自定义源限 `https://`；
`ConfigBackup.import()` 校验 URL scheme。

#### 3.5 连接归属解析阻塞数据面

- `native/mirage-core/src/tun/tcp.rs:310` — `resolve_package()` 同步调用，每条新 TCP 连接必经
- `native/mirage-core/src/tun/udp.rs:167` — 同上
- `ConnectionOwnerResolver.kt:62` — 缓存键 `(protocol << 32) | srcPort`
- `ConnectionOwnerResolver.kt:82` — miss 时 `cm.getConnectionOwnerUid()`，跨 Binder 同步 IPC
- `native/mirage-jni/src/lib.rs` — tokio runtime `.worker_threads(2)`

缓存键含 srcPort，而 srcPort 每条连接都不同，512 条 LRU 对新连接命中率≈0。
所以实际是「每条新连接一次同步 Binder 往返」，而 tokio 只有 2 个 worker——
两条连接同时卡在 Binder 上，整个数据面停摆。网页加载典型 6–12 条并发连接会被串行化。

附带：`ConnectionOwnerResolver.kt:72` 与 `:83` 每次 miss 打两条 `Log.i`，release 也打。

**修法**：把归属解析移出建连关键路径——`spawn_blocking` 异步执行，
解析完成后回填给 monitor，不阻塞 `relay_tcp`。前置的 `portCache` 应删除或改为按 UID 缓存
（`uidToPackageCache` 本身是对的，问题只在 srcPort 那层）。

---

### 第 4 批 — 工程基线

| 项 | 当前实测值 | 目标 |
| :--- | :--- | :--- |
| `cargo clippy --lib -- -D warnings` | **35 errors** | 0，并纳入 CI |
| `cargo fmt --check` | **fail** | pass，并纳入 CI |
| `catch_unwind`（`mirage-jni/src/lib.rs`） | **0** 处 / 43 个 `pub extern "system"` 入口 | 统一宏包裹，panic 返回错误码而非 abort `:core` |
| Kotlin 测试文件 | **1** 个（`PerAppFilterTest.kt`） | 覆盖 `CoreService` 状态机等核心路径 |
| CI | **无** `.github/` | lint + clippy + fmt + 单元测试 |
| gradle wrapper | **无** | `gradle wrapper --gradle-version 8.9` |
| AGP / Kotlin 版本 | 裸字符串字面量 | 版本目录 `gradle/libs.versions.toml` |
| `android.ndkVersion` | 未设置（仅硬编码在构建脚本里） | 写入 `android {}` 块 |
| `rust-toolchain.toml` | 无 | 两个 crate 各加一份 |
| `[profile.release] strip` | 未设置，`.so` 未 strip、约 3.9 万符号 | `strip = true` |
| `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` | 清单声明，Kotlin 侧 **0** 引用 | 从权限与 `foregroundServiceType` 中一并摘掉 |
| `allowBackup` | `true`，无 `dataExtractionRules`，节点密码明文存 SharedPreferences | 排除节点 prefs，或置 `false` |

`startForegroundCompat` 的第三层兜底 `startForeground(1, notif)` 未包在 `try` 内
（`CoreService.kt` 的 `startForegroundCompat`），是崩溃路径，摘掉多余 FGS 类型后可一并收敛。

---

## 3. 已知遗留 / 待确认

1. **`native/mirage-core/src/tun/tcp.rs` 首包预读 `15ms → 60ms`**：
   已独立验证并提交（`f3274b1`）。
2. **release 包当前未签名**（`app-release-unsigned.apk`），因为未配置 keystore。
   这是设计行为。要出可安装包需按 README 配置 `keystore.properties` 或 `MIRAGE_KEYSTORE_*`。
3. **实机验证**：
   已在 Sony SO-02K (Android 9 / API 28) 与 Samsung Galaxy S24+ (Android 16 / API 36) 双机实测通过：
   - 点断开后 VPN 接口彻底拆除，不再自己回来
   - 断开后无幽灵连接与无死循环退避
   - 物理网络监听与 NDK 原生句柄绑定严格一致
4. **`jniLibs/` 是 gitignore 的本地产物**。改了 `native/` 后必须
   `bash scripts/build-android.sh native`，否则 APK 里仍是旧 `.so`。

---

## 4. 审计约束：以下三处**不要**照抄 meow

对照实现位于 `/opt/reference/meow-android`（本地源码，逐行比对，非凭记忆）。
同构不等于更优——以下三处 Mirage 现状正确，照搬 meow 是退步：

| 切面 | meow 现状 | 结论 |
| :--- | :--- | :--- |
| 前台服务 | `MeowApp.kt:260-265` 注释自陈：从不调 `startForeground` | meow 的 VPN 服务在内存压力下会被杀。Mirage 是前台服务，**正确**，保持 |
| 网络监听 | `DefaultNetworkListener.kt` 仅 35 行，用 `NET_CAPABILITY_NOT_RESTRICTED` | 不排除 VPN 网络，可能把自己的 tun 当作底层网络。Mirage 用 `NOT_VPN` **更正确** |
| `setUnderlyingNetworks` | `VpnService.kt:134` 仅在 establish 时设一次，之后永不更新 | Wi-Fi→蜂窝切换后仍声称已死的底层网络。Mirage 持续更新的**方向是对的**，问题只在实现自相覆盖（见 2.2） |

值得从 meow 取用的，全部集中在 `core/src/main/java/io/github/madeye/meow/bg/BaseService.kt`
（状态机、入口守卫、`cancelAndJoin`、`catch (Throwable)`、`RemoteCallbackList`），
第 1 批已采纳。

另：**sing-box 无本地副本**，本轮审计未引用其内部实现，也请勿凭记忆虚构其文件路径。

---

## 5. 结论采信规则

本轮审计由三个模型分领正交切面并行执行，全部结论由主审逐条回源核实后才写入。
沿用 AGENTS.md 第 1 条：**任何来源的结论都需独立验证后再落地**。已发生的误判示例，
供后续避免重复：

- 「meow 从不调 `startForeground`」初查 grep 有 2 处命中，核对后两处均在注释内——原结论成立。
- 「五个 job 全部重复」实际 `failoverRestartJob` 有 `?.cancel()` 保护，只有 4 个无保护。
- `mirage_routing_prefs` 双写被标为 HIGH，实际该文件仅一个键，无现患，下调为 MEDIUM。
- `crypto/aead.rs` 的 `pop().unwrap()` 看似可从网络输入触发 panic，
  但紧邻上方有 `if self.buffer.is_empty() { return Err(...) }` 守卫，可证安全——**未计入缺陷**。
