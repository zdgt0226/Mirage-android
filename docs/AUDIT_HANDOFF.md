# Android 客户端审计 — 交接文档

> 面向接手本项目的协作者与 AI Agent。
> 配套可视化路线图：<https://claude.ai/artifact/FaiBQfWKqSji1sFDVzHtsA>
>
> **审计基线** `fd6cd3e` · **已完成** 第 0、1、2、3、4 批（`cade5d7`、`3aeb445`、`fbf26f1`、`d9e3280` 及第 4 批工程基线）及原生 60ms 预读（`f3274b1`）· **全量计划审计与工程加固已闭环**
>
> 本文所有 `file:line` 基于 `d9e3280`。引用旧行号的历史记录已失效，以本文为准。

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
    export PATH=/opt/jdk-17/bin:$PATH
    cd /workspace && ./gradlew :app:compileDebugKotlin --no-daemon -q
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

`adb devices` 通常有设备在线（如 `R5CX21FD9PX` 或 `BH905W2A9G`）。AGENTS.md 要求真机验证优先。

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

#### 第 3 批 — `d9e3280`（配置与数据：解耦跨进程 IPC、归属解析移出建连热路径、加固 Geo OTA）

| # | 问题 | 处置 | 验证方式 |
| :-- | :--- | :--- | :--- |
| 1 | 3.1 跨进程配置不一致：UI 与 `:core` 将 SharedPreferences 作为跨进程通信，`:core` 缓存永不刷新且 failover 双向写竞争 | 彻底消除 SharedPreferences IPC：`startVpn` 全量打包 Intent extras、`:core` 内存持有 `ServiceConfig`、AIDL 补齐热更新、`doFailover` 移除 `:core` 侧 `NodeStore.setSelected` 仅单向回调 UI 落盘 | 容器内编译与单元测试通过；实机测试动态修改设置无异常 |
| 2 | 3.2 `mirage_routing_prefs` 双进程写，整份快照落盘相互覆盖 | 移除 `CoreService` 侧对 `mirage_routing_prefs` 磁盘写，只调用 `MirageNative.setOutboundMode`，持久化收归 UI 进程 | 实机验证分流模式切换（规则/全局/直连）即时生效 |
| 3 | 3.3 Geo 替换非原子且失败静默报成功（`delete() + renameTo()` 空窗风险） | 使用 `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` 原子替换，异常严格捕获并上报 `GeoUpdateResult.success = false` | 实机下载验证：`.tmp` 稳步写入，未产生文件丢失空窗 |
| 4 | 3.4 Geo OTA 零完整性校验且无 URL scheme 约束 | 强制限制 `https://` 协议白名单；下载 `.sha256sum` 并完成完整性校验；`ConfigBackup` 严格校验备份 URL | 单元测试与实机镜像下载双向验证 |
| 5 | 3.5 连接归属解析阻塞数据面，0% 命中率缓存与 Binder 同步 IPC 串行化建连 | 移除关键路径同步归属阻塞，改由 `tokio::task::spawn_blocking` 异步解析并回填至 `monitor::update_conn_app`；删除 0% 命中率的 `portCache`，保留 `uidToPackageCache`，诊断日志降级为 `Log.d` | 实机并发连接测试：4+ 连接同时秒级放行，UI 监控列表异步回填「Google Play 服务」与「X」应用归属 |

#### 第 4 批 — 工程基线与质量门禁

| # | 问题 | 处置 | 验证方式 |
| :-- | :--- | :--- | :--- |
| 1 | Rust 模块 35 项 Clippy 告警与代码格式不一 | 修复两 crate 全部 35 项 Clippy 告警；全面执行 `cargo fmt` 统一代码样式 | `cargo clippy --lib -- -D warnings` 为 0；`cargo fmt --check` 0 差异通过 |
| 2 | `mirage-jni` 43 个入口裸露无 panic 屏障，跨 FFI panic 将直接导致 `:core` 进程 abort 退出 | 定义 `jni_boundary!` 宏，全量包裹 43 个 `pub extern "system"` JNI 函数，panic 安全拦截并返回错误默认值 | 人工代码审查与 43 个 JNI 符号保持验证 |
| 3 | 原生动态库 `.so` 携带 4 万+ 符号未 strip，体积臃肿且工具链版本漂移 | 配置 `[profile.release] strip = true, lto = true`；新增 `rust-toolchain.toml` 锁定通道与目标架构 | `libmirage_jni.so` 从 7.0MB 降至 **3.9MB** (-44%)；APK 从 14MB 降至 **10MB** |
| 4 | 清单声明无引用的 `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`，`allowBackup=true` 存在明文配置泄露风险，FGS 第三层兜底未捕获异常 | 清单摘除 `SYSTEM_EXEMPTED` 权限及类型，设置 `allowBackup="false"`；`CoreService.kt:startForegroundCompat` 统一 try-catch 保护 | 人工审查；实机安装并在 Android 9 / API 28 正常启动前台服务 |
| 5 | 构建依赖硬编码、缺乏 Gradle Wrapper、缺少 CI 自动化检查 | 迁移至 `gradle/libs.versions.toml` 统一管理版本；显式声明 `ndkVersion`；生成 Gradle 8.9 wrapper 并改造构建脚本；新增 `.github/workflows/ci.yml` 覆盖 Rust 与 Android 全质量门禁 | 容器内 `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` 成功；CI 配置完备 |
| 6 | Kotlin 单元测试薄弱（原仅 1 个文件） | 新增 `CoreServiceStateTest.kt`（状态机流转）、`NodeStoreTest.kt`（URI 解析与存储），引入 `org.json` JVM 测试实现 | 容器内 14 个测试全量 SUCCESS (3m 47s) |

### 全量验证结果汇总（容器内实测与实机）

```
compileDebugKotlin     clean（0 错误）
testDebugUnitTest      14 passed, 0 failed (BUILD SUCCESSFUL)
cargo clippy           0 warnings (-D warnings)
cargo fmt --check      clean (0 diff)
cargo test --lib       131 passed, 0 failed
实机安装与连接          Sony SO-02K (Android 9 / BH905W2A9G) 实测通过：10MB 瘦身包秒装、前台服务正常、隧道流量吞吐平稳
```

---

## 2. 后续建议与展望

Mirage-Android 架构审计与工程基线（第 0、1、2、3、4 批）现已全部闭环。
后续可关注的增强点：
1. **正式签名发布**：配置 `keystore.properties` 或 CI Secrets (`MIRAGE_KEYSTORE_*`) 产出可签名的 Release APK。
2. **多架构扩充**：当前默认仅编译 `arm64-v8a`，如需支持模拟器或 32 位老旧设备，可在 `build-android.sh` 中扩展 `x86_64` / `armeabi-v7a`。


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

### 5.1 测试有效性规则

**测试必须调用生产函数，不得在测试内重写同一份判断逻辑。**

反例（本项目真实出现过四次）：

```kotlin
// 声称覆盖 GeoManager 的 https 校验，实际在测 Kotlin 标准库
assertTrue(validHttps.startsWith("https://", ignoreCase = true))
```

改坏 `GeoManager` 的校验，这条断言照样绿——等于没有防线。

正确做法是让生产代码把判断暴露成可测的纯函数，测试直接断言它：

```kotlin
assertFalse(GeoManager.isBuiltinUrl("https://raw.githubusercontent.com/attacker/..."))
```

若逻辑散在 `Context`/IO/原生依赖之间无法直接触达，**先抽成纯函数再测**
（参见 `CoreService.StateMachine`、`tun/tcp.rs` 的 `may_query_upstream`），
而不是退而求其次在测试里复述条件。

**新增或修改测试后做一次变异验证**：把被测逻辑改坏，确认对应用例变红；
不变红就说明该用例没有防护力。本文档记录的每条测试改动都经过这一步。
