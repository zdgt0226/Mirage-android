# Android 客户端审计 — 交接文档

> 面向接手本项目的协作者与 AI Agent。
> 配套可视化路线图：<https://claude.ai/artifact/FaiBQfWKqSji1sFDVzHtsA>
>
> **审计基线** `fd6cd3e`
>
> **第一轮（计划内）** 第 0–4 批：`cade5d7`、`3aeb445`、`fbf26f1`、`d9e3280`、`7b822f1`，
> 外加原生 60ms 预读 `f3274b1`。
>
> **第二轮（对第一轮的多模型复审与修复）** `d3474a5`、`938a7cd`、`be44e31`。
> 复审在第一轮「已闭环」的成果里查出 11 项缺陷，其中 7 项是第一轮修复自身引入的。
> 详见 [§1.5](#15-第二轮多模型复审对第一轮成果的返工)。
>
> **第三轮（真机回归与返工）** `1f91fc8`、`05536ed`。
> 六项回归在 Sony SO-02K 上实跑，查出 4 项容器测试无法触及的缺陷，
> 并完成了绑定生命周期的架构改动。详见 [§1.6](#16-第三轮真机回归查出的问题)。
>
> **第四轮（CI 首跑与 action 升级）** `d7f628c`、`974da07`。CI 自建立起从未通过过，
> 两处失败均在 workflow 本身；随后六个 action 全部升离 Node 20。
> 详见 [§1.7](#17-第四轮ci-首次通过)。
>
> 本文所有 `file:line` 基于 `d7f628c`。引用旧行号的历史记录已失效，以本文为准。

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

> **自定义内核（运行时换核）的符号集门禁**：`CoreManager.importCore` 与 `NativeLoader.load`
> 都会调 `NativeSymbolPolicy.verify()`，比对 `.so` 的 `.dynsym` 导出与 `MirageNative`
> 反射出的 native 方法清单，缺符号即拒绝（fail-closed：解析不出符号表同样拒绝）。
> 为什么需要它：`ElfChecker` 原先只读 ELF 头的 `e_machine`，架构对但接口面偏旧的 `.so`
> 能 `System.load` 成功，之后每次调用缺失方法抛 `UnsatisfiedLinkError` 被 `NativeGuard`
> 兜住并返回默认值——**静默降级**，功能整段失效却不崩。
> 实测依据（2026-11-03）：上游 Mirage-rs run `41558057174`（`62cf686`, v0.17.3）的
> `mirage-jni-so` 产物导出 43 个 JNI 符号，本仓库 `MirageNative` 声明 46 个，缺
> `setNodeList` / `setFailoverPolicy` / `onNetworkChanged`——该 run 启动于 `01:11:18Z`，
> 早于 PR #22 合并（`01:36:47Z`）25 分钟。
> 门禁依赖 `-keep class com.mirage.android.core.MirageNative { *; }`：规则收窄会让它
> 拒绝一切自定义内核（方向安全，但会挡掉合法内核）。

> **注意（新 worktree 必须先构建原生库）**：`android/app/src/main/jniLibs/` 被 `.gitignore` 忽略，新 worktree 中不存在 `libmirage_jni.so`；打 APK 前必须先按仓库脚本构建原生库并放入 jniLibs，安装前用 `unzip -l <apk> | grep lib/` 核对含 `lib/arm64-v8a/libmirage_jni.so`（教训：2026-09-26 曾因此打出无内核的 release 包）。

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

**NDK 版本的唯一来源是 `android/gradle/libs.versions.toml` 的 `[versions] ndk`。**
改它一处即可，`build.gradle.kts` / CI / `build-android.sh` 都从那里取；
不要在任何地方写第二份（原因见 §1.7）。

**工具链版本固定在仓库根的 `rust-toolchain.toml`（当前 `1.95.0`）**，crate 级不放副本
——rustup 就近优先，crate 级文件会静默覆盖根目录设置。升级 rustc 是一次显式改动：
改版本号 → 本地跑通 `clippy -D warnings` / `fmt --check` / `test` → 提交。
不要改回 `channel = "stable"`：CI 跑 `-D warnings`，浮动 channel 意味着上游发版即 CI 红
（§1.7 实测踩过）。

#### 引擎句柄只能取 `stack.engine()`（2026-11-05 真机实测）

`mirage-jni` 的 `RunState` **刻意不存** `Arc<Engine>` 快照，唯一来源是
`state.stack.engine()`（读 `TunStack` 内部的 `ArcSwap`，永远是当前引擎）。

原因：`setNode` 热切换节点走 `stack.swap_engine(new)`，而 `swap_engine`
（`native/mirage-core/src/tun/mod.rs:124-128`）会对旧引擎调 `shutdown()`。
任何留在 `RunState` 里的快照句柄在那一刻就指向死引擎，而且**永远不会恢复**：
健康判据是 `PoolStats::is_healthy() == (consecutive_failures < 3)`
（`proxy/pool.rs:242-244`），唯一归零路径是 `record_latency`（一次成功建连）；
`WarmPool::shutdown`（`pool.rs:911-921`）只置标志并清空空闲队列，**不动 stats**。
已关停的池不会再有任何成功 → 计数永久冻结 → `isHealthy()` 恒为 false。

实测后果（SO-02K，两个本地服务端 8443/9443，`kill` 掉当前节点）：

| 时刻 | 事件 |
| --- | --- |
| 01:19:44 | 杀掉 A，`Connection refused` 开始 |
| 01:20:04 | 外壳看门狗连续 2 次判异常 → 触发切换 |
| 01:20:07 | `[failover] 切换到: local-B (9443) (75ms)` — 切换**成功**，B 开始中继真实流量 |
| 01:20:22 / 01:20:37 | 又连续 2 次判异常 — **误报**，B 是健康的 |
| 01:20:40 | `当前节点仍最优, 重启连接` → 无谓的整核重启 |

即每次节点热切换成功后，必然在 2 个巡检周期（`checkIntervalSec` 默认 15s）后
多做一次整核重启。修复：4 处 `state.engine` / `s.engine` 全部改走
`state.stack.engine()`，并把 `RunState.engine` 字段**删除**，使这类回归写不出来。

**对 P3 的影响**：内核自己的故障切换看门狗（`mirage-jni/src/lib.rs` 的
`spawn_failover_watchdog`）读的是同一个句柄。P3 若直接删掉外壳看门狗改由内核决策，
会把同一个误报从外壳搬进内核 —— 所以这条是 P3 的前置修复。

回归护栏：`proxy::pool::stale_handle_tests` 两条单测钉住「死池永不自愈」与
「只有一次成功建连能归零」这两个前提。

#### 故障切换已下沉内核，外壳只剩重启执行（P3，2026-11-05）

`CoreService` 不再有看门狗。`startFailoverWatchdog` / `doFailover` /
`failoverRestartJob` / `watchdogJob` 已删除（103 行）。判定与选路在
`mirage-jni/src/lib.rs` 的 `spawn_failover_watchdog`，外壳只做两件事：
按 `EVENT_SWITCHED` 对齐自己的节点副本、按 `EVENT_RESTART_REQUESTED` 重建隧道
（`establish()` 是框架 API，内核拿不到 fd）。

**改这块之前必须知道的五条**：

1. **UI 进程不能调 `setNodeList`。** 原方案写过「`VpnRepository` 的 `pushNodes`
   改调 `setNodeList`」——那是笔误。原生库只装在 `:core`，UI 进程直接调 JNI 必抛
   `UnsatisfiedLinkError`。路径是 UI → AIDL `updateNodes` → `:core` 内
   `MirageNative.setNodeList`，自 P1 起已通。

2. **事件处理必须转线程。** `onFailoverEvent` 的调用链是 内核看门狗任务 →
   `failover::emit_event` → JNI 上行，全程在 tokio worker 线程上。在该线程上同步调
   `MirageNative.stop()`，Rust 侧会 take 走 `RUNTIME` 并 drop `Arc<Runtime>`；一旦那是
   最后一个引用，就等于在 runtime 自己的 worker 线程里 drop runtime —— tokio 直接
   panic，而 panic 发生在 spawn 的任务里，`jni_boundary!` 捕不到，表现为内核静默
   失去看门狗。`dispatchFailoverEvent` 因此一律 `scope.launch`。

3. **外壳的两条隐式行为不在内核里，必须显式补。** 删外壳代码时最容易丢的就是这种
   没有名字的 `continue`：

   | 外壳原有 | 内核补法 |
   | --- | --- |
   | `if (currentPhysicalNetwork == null) continue` | 读 `failover::network_state().1`，`false` 时跳过整轮并把连续失败计数归零。**但它的输入目前是坏的**，见下方「网络闸门的输入是陈旧的」 |
   | `failoverBackoffSec` 每轮 `+5s`、上限 `30s` | `failover::escalate_backoff()`（纯函数 + 单测）。看门狗累加 `dynamic_backoff_sec`，与外壳下发的静态 `policy.backoff_sec` **相加**；外壳固定下发 0，否则两处叠加。**作用域比字面窄**：该变量是看门狗任务的局部量，而 `start()` 每次都重起看门狗（`WATCHDOG_GEN` 让旧实例退出），所以一旦走到「请求重启」退避就归零 —— 真机实测三轮重启间隔恒为 37s 而非递增。外壳同理（`failoverBackoffSec` 活在被 `cancelPeriodicJobs()` 取消的 `watchdogJob` 闭包里），两边等价，不是回归 |

4. **三个 AIDL setter 必须重推策略。** `setAutoReconnect` / `setCheckInterval` /
   `setFailoverMode` 原先只改 `serviceConfig`，由外壳看门狗每轮实时读。下沉后内核拿的是
   启动时快照，三者各自调 `pushFailoverPolicy()`，否则表现为「改了检查间隔要重连才生效」
   「关掉自动重连后内核仍自己换节点」。

5. **`serviceConfig.uri` 会过时。** 内核热切换只改自己的 `CURRENT_NODE_URI`，而
   `startLocked` 在没有 `uriOverride` 时正是取 `serviceConfig.uri` 建连。不在
   `EVENT_SWITCHED` 里同步，下一次重启就会回到刚被判死的节点。

#### 网络闸门的输入是陈旧的（2026-11-05 实测，P3 之前就存在）

`failover::network_state().1` 的唯一写入方是 `CoreService.switchTo()` → `onNetworkChanged`。
实测 `switchTo(null)` **不会**在底层物理网络消失时被调用：

| 时刻（本地） | 事件 |
| --- | --- |
| 09:01:03 | `svc wifi disable` + `svc data disable`；`dumpsys connectivity` 报 `Active default network: none` |
| 09:01:10 / 09:01:47 / 09:02:24 | 内核照旧判不健康并向外壳要了三次重启 —— 闸门没拦住 |
| — | `core.log` 与 logcat 全程没有 `底层物理网络切换: 400 -> null` |
| 09:04:16 | `svc wifi enable` 后才出现 `底层物理网络切换: 400 -> 409` —— 回调活着，但 `currentPhysicalNetwork` 整段时间冻结在 400 |

机制：注册的是 `cm.registerDefaultNetworkCallback(cb)`（`CoreService` 的
「注册底层物理网络监听」一段），它只跟踪**默认网络**的生命周期。隧道建立后底层物理网络
不再是被跟踪的那一个，它的 `onLost` 不送达，`onCapabilitiesChanged` 也不对它触发，
于是 `currentPhysicalNetwork` 保持陈旧。

**不是 P3 引入的。** 外壳原看门狗的 `if (currentPhysicalNetwork == null) continue` 读的是
同一个陈旧字段，在它被写出来针对的那个场景里同样是死代码。下沉后行为等价，闸门的代码
路径与单测都已就位。要让它真的生效，须先把回调改成按 `NetworkRequest`
（`NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_NOT_VPN`）注册的 `registerNetworkCallback`；
那会同时改动 `setUnderlyingNetworks` 的时序与暖池冲刷的触发点，需要自己一轮真机矩阵。

**无声失败的防线。** Rust 侧 `FailoverPolicy` 每个字段都带 `#[serde(default)]`，键名拼错
会被静默丢弃并回落到 `enabled = false`。外壳看门狗已经删了，结果是**无人**做故障切换，
日志里却只有一行 `切换策略下发内核: ok=true`。策略 JSON 的构造抽成纯对象
`CoreService.FailoverControl`，`CoreServiceStateTest` 逐字段断言键名、断言 `enabled`
跟随「断线自动重连」、断言静态 `backoff_sec` 为 0、断言内核事件只在
`ServiceState.Running` 下执行（`shouldHonorEvent`）——「请求重启」在非 Running 下放行会
复活用户刚停掉的 VPN，「已热切换」在非 Running 下放行会把下标回流落盘到 UI 的
`selected_index`，表现为用户停掉 VPN 后选中的节点被悄悄换了。

#### `src/vendor/` 不参与编译（2026-11-03 实证）

`native/vendor-sync.sh` 把上游 22 个模块复制到 `native/mirage-core/src/vendor/`，
但**那棵树不参与编译**：`lib.rs` 对它的唯一引用是 `include_str!("vendor/SYNC.md")`
（一个版本字符串），全树没有 `include!` 也没有 `#[path]`。实际编译的是手工裁剪树
`src/{crypto,proxy,dns,config.rs,monitor.rs,time_sync.rs,node_uri.rs,net_util.rs}`。

证据（三条独立）：

1. `vendor/proxy/mod.rs` 声明了 `shadowsocks` / `wg` / `splice` / `mixed` / `healthcheck`
   等在 vendor 树中**并不存在**的模块文件 —— 若它参与编译，crate 根本编不过；
   而把 vendor 同步到 `62cf686` 之后 `cargo check` 仍然通过。
2. `cargo fmt --check` 通过 —— vendor 树里的上游代码未被纳入格式化范围，
   说明它不在模块树里。
3. 编译树与 vendor 副本本来就有大量差异（`config.rs` 2894 行、`monitor.rs` 1751 行、
   `pool.rs` 1284 行），那是裁剪的结果，不是"该同步没做"。

**后果一：跑完 `vendor-sync.sh` 协议行为不会有任何变化。** 它刷新的是"上游原版参考副本"，
供人工比对；要真正升级协议，必须把差异手工移植进 `src/` 裁剪树。
`SYNC.md` 里的 commit 号只代表参考副本的版本，**不代表本 APK 的协议行为**。

**后果二：上游 Mirage-rs `build.yml` 的 `android-core` job 给的是伪保证。** 它的注释写
"把本仓库当前 commit 的协议核 vendor-sync 进去再交叉编译 → 保证当前协议永远是 Android
可编译的"，但它 sync 进的是惰性参考树，编的仍是旧的手工裁剪码。该 job 绿灯**不能**
用来证明上游新协议与移动端裁剪边界兼容。（本轮一度据此下过结论，已更正。）

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

> ⚠️ **本批当时的论断已被推翻**：原文写「未采用 `cancelAndJoin`，因为状态守卫已堵住竞态」。
> 守卫当时并未生效 —— `stopInternal` 全程持锁，`Stopping` 对其他线程永不可见。
> 见 §1.5 的 C 项。上表 `CoreService.kt` 行号也因后续改动失效，以现文件为准。

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

### 1.5 第二轮：多模型复审对第一轮成果的返工

第一轮宣告「全量闭环」后，对第 0–4 批的产出做了一次多模型对抗审计
（三个模型分领 Geo 完整性 / 状态机与并发 / CI 与测试质量三个正交切面，
结论由主审逐条回源核实）。查出 **11 项缺陷，其中 7 项由第一轮的修复自身引入**。

修复分三批落地。

#### 第二轮第 1 批 — `d3474a5`

| | 问题 | 处置 |
| :-- | :--- | :--- |
| C | **状态机守卫从未生效**。`stopInternal` 在一个 synchronized 块内走完「置 Stopping → 拆除 → 置 Stopped」且无挂起点，按 JMM monitor happens-before，阻塞在同一把锁上的线程重新获得锁时看到的必然是 `Stopped`。守卫不可达，「断开后自己重连」的竞态**从未闭合** | 拆成三段：短锁提交 `Stopping` 并释放 → 锁外拆除 → 再取锁置 `Stopped`。锁外拆除安全，因为此刻任何 `startInternal` 都会在守卫处退出。`onDestroy` 同样处理 |
| H | **CI 的 JNI 门禁有假阴性**。`protectFd` 声明于两处（`MirageNative.kt` 与 `CoreService.kt`），各由独立 keep 规则保护，未限定类的 `grep -c protectFd` 会被后者掩护 | 断言改全限定名 + 签名；新增强不变式：dex 内 `MirageNative` 的 NATIVE 方法数必须等于 `.so` 的 `Java_com_mirage` 导出数 |
| D | `pushNodesToCore` 丢弃 Job，`scope` 为进程级且停止时不取消。两次推送各持启动时快照，慢的后到覆盖快的；停止后推送仍落地 | 跟踪 Job，启动时 cancel-and-replace，`stopVpn` 时取消 |
| G | `isBuiltinUrl` 用 `ignoreCase`，与自身「完整 URL 相等」的注释不符。GitHub/jsDelivr 路径大小写敏感，拼写变体被判成内置源后强制校验、双双 404、本可避免的硬失败 | 改为逐字节相等，注释写明方向性（true 是严格分支，假阴性才危险且不可能发生） |
| I | CI：`ANDROID_NDK_HOME` 未导出给 cargo-ndk（`local.properties` 只有 Gradle 读）；`echo "y"` 只答一次许可；build-tools 依赖 AGP 隐式下载；cargo-ndk 未固定版本；无缓存 | 逐项补齐 |
| J | 两次提交都引用了 §5 里并不存在的「测试必须调用生产函数」规则 | 补写 §5.1，使引用成立 |

**H 的变异验证**：移除 `MirageNative` keep 规则的 `{ *; }` 后重建 release ——
旧断言返回 2 判通过，新断言返回 0 且 native 数 40≠43，失败。双向都验过。

#### 第二轮第 2 批 — `938a7cd`

| | 问题 | 处置 |
| :-- | :--- | :--- |
| A | **`verified` 是死代码**。`GeoUpdateResult.verified` 在 `GeoManager` 之外零消费点，`MainActivity.checkGeoInitialization` 连返回值都不接。首启用户的未校验数据被静默装进持有 TUN 的 `:core`。第一轮提交信息声称「UI 会明示」，那只对手动路径成立 | `updateGeoFiles` 加 `allowUnverified`（默认 false），判断置于原子替换**之前**；自动路径拒绝安装；手动路径弹阻塞式对话框；`verified` 持久化并进入 `displaySummary`（「已就绪（未校验）」） |
| B | **fail-closed 撞上默认源单镜像**。默认 `fastly_cdn` 的镜像列表 `distinct()` 后仅 2 个，另一个是墙内被封的 `raw.githubusercontent.com`。`fetchSha256` 无重试且不分 404 与 5xx，一次瞬时故障即拒绝整次更新。威胁模型也反了：断不了几 MB `.dat` 的对手，断得了几十字节的 `.sha256sum` | `fetchSha256` 三态化（`Found`/`Absent`/`Unavailable`）+ 对 `Unavailable` 退避重试；失败原因分级传到用户面前，「疑似被篡改」与「网络暂时不可用，请稍后重试」不再是同一句话；摘要响应加 4096 字符读取上限 |

**联网实测**：六个内置源 `.sha256sum` 全部存在，fail-closed 不会破坏正常更新。
但 v2fly 的 `geoip.dat.sha256sum` 曾连续 4 次返回 500、随后连续 10 次 200 ——
GitHub release 资产 CDN 确实会抖，这正是必须重试与三态化的实证依据。

#### 第二轮第 3 批 — `be44e31`（原有缺陷，非第一轮引入）

| | 问题 | 处置 |
| :-- | :--- | :--- |
| E | **并发架空完整性校验**。`updateGeoFiles` 零互斥且暂存路径固定。B 校验完，A 截断重写同一文件，B 的 `Files.move` 装 A 的字节却报 `verified = true`。旋转屏幕重入 `checkGeoInitialization` 即可触发，无需攻击者 | `Mutex` 串行化，第二个调用方立即返回而非排队重下；每次运行用 `File.createTempFile` 独立暂存 |
| F8 | 两次 `Files.move` 之间失败留下「新 geosite + 旧 geoip」错配，而 `isReady` 是 `\|\|`，该状态还会被判为就绪、抑制首启重试 | 旧文件先挪 `.bak`，任一步失败整体回滚；`isReady` 改 `&&` |
| F | `downloadFile` 对无 `Location` 的 3xx 执行 `?: break`，带着活连接掉进写盘块，把重定向响应体当产物；重定向次数耗尽时也只是碰巧失败 | 两处改显式 `return false`；重定向目标复查 https 防降级 |
| F5 | 摘要与产物同源，防不住控制该源的攻击者 | **刻意不修**。建议的跨源取摘要方案要用 `raw.githubusercontent.com`，而它正是墙内被封的 host —— 会让 fail-closed 必然触发，把 B 项刚修完的可用性问题重造一遍。已在 `fetchSha256` 文档注释中写明能力边界，真实性保证列为后续项 |

### 1.6 第三轮：真机回归查出的问题

在 Sony SO-02K (Android 9 / API 28) 上实跑 §3 列的六项回归。
六项全部通过，但过程中查出 **4 项容器测试无法触及的缺陷**。

#### 第三轮第 1 批 — `1f91fc8`

| | 问题 | 处置 |
| :-- | :--- | :--- |
| 1 | **节点推送在冷启动下必失败**。`:core` 是独立进程，`startForegroundService` 后还要冷启动并加载数 MB 原生库。设备日志：推送在 `09:03:34.236` 耗尽 10×300ms，`:core` 在 `09:03:34.866` 才「内核已启动」，晚 0.63 秒。回落到 `:core` 本地陈旧读取 —— 正是整条线要消除的东西 | 轮询本身是错的形状（预算给多少都是猜）。改为 `CoreController.runWhenConnected`：已绑定立即执行，否则挂 `onServiceConnected`。同 key 覆盖保留最新快照，`stopVpn` 撤销待办 |
| 2 | **暂存文件泄漏**。两个 tmp 在前面一次性创建，而 GeoSite 下载失败的早退只删 `siteTmp`，`ipTmp` 泄漏成 0 字节孤儿；唯一命名意味着只增不减 | staged 主体抽成独立函数 + `try/finally` 统一清理。真机随即暴露 `finally` 覆盖不到的情况：被 `force-stop` 杀掉的运行会留下 tmp（设备上已累积 3 个），故又加了启动清扫（持 `updateMutex`，不会误删并发运行的文件） |
| 3 | **CDN 偏斜被误报为篡改**。默认源 `fastly_cdn` 实测不匹配：期望 `cc45cb…`、实得 `e6563d…`。jsDelivr 把产物与摘要当两个独立缓存对象，而 `@release` 是可变 ref，上游重建后边缘失步。回退到 `raw.githubusercontent` 后校验通过、更新成功，但文案把日常现象说成攻击 | 文案改为以缓存未同步为首要解释、篡改作为持续不符时的可能；内置镜像不匹配时先带缓存绕过重取一次摘要再判死（几十字节，很便宜） |

> 第 3 项此前被审计模型标为「机制推断、未实测」。**第三轮在默认源上实测到了**，
> 且发生在改动上线数小时内 —— 说明这不是边缘情况。

#### 第三轮第 2 批 — `05536ed`（绑定生命周期，架构改动）

`CoreController.bind` 原先在 `VpnRepository.init` 调用，而该仓库是进程级单例，
于是 `BIND_AUTO_CREATE` 这条引用与 UI 进程同寿。真机实测：断开 VPN 后 `stopSelf`
已生效（dumpsys 无 `started=true`），进程却仍被 `AppBindRecord` 吊着。
**这正是 `:core` 的 SharedPreferences 缓存永不刷新的根因**，也是第 3 批那套
`ServiceConfig` + AIDL 绕行之所以必需的原因。

改为跟随 Activity 可见性绑定/解绑。解绑不影响运行中的 VPN ——
`CoreService` 是 started foreground service，只有 `stopSelf`/`stopService` 能终止它。

| 场景 | `:core` 实测状态 |
| :--- | :--- |
| VPN 运行中 + 退后台 | `oom_score_adj=100`，隧道 HTTP 204 通，tun0 增量 3375 字节 |
| VPN 已断开 + 退后台 | `cch-empty`（不持有任何组件，随时可回收） |

验证该改动时又撞出两个缺陷，同批修掉：

| | 问题 | 处置 |
| :-- | :--- | :--- |
| 4 | **Activity 计数失步导致断开按钮静默失效**。`VpnRepository` 懒构造（ViewModel 首次访问），注册生命周期回调时首个 Activity 的 `onStart` 已过去，计数从一开始少一。用 `incrementAndGet() > 0` 作绑定条件，回前台得 0、条件不成立 → 绑定永不恢复 → `CoreController.stop()` 走 `call{}` 返回 null → **用户点断开毫无反应**，日志里只有一行 `bound=false` | `bind()` 改为无条件调用（自身幂等），递减钳位到 0 |
| 5 | **回前台闪一次错误状态**。绑定随可见性后，每次回前台有 120–170ms 异步窗口，窗口内 `isRunning()` 恒 false，`checkCurrentState` 会把连着的 VPN 写成 Disconnected | 未绑定时直接返回，交由 `onServiceConnected` 推真实状态 |

顺带把 `unbind` 做安全：单独跟踪 `bindRequested`（`unbindService` 对未绑定的
connection 会抛异常），并解开 death recipient。

#### 一个测量教训

中途观察到 `unbind` 紧跟 `onServiceConnected` 12ms 后触发，一度判为逻辑缺陷。
实际是此前执行 `adb shell svc power stayon false` 关掉了常亮，**屏幕休眠导致
Activity stop**。做真机时序测量前务必 `svc power stayon usb`，否则测的是自己
造出来的现象。

同类教训还有一次：用 `uiautomator dump` 定位控件时读到了 `/sdcard/ui.xml` 的
**陈旧副本**（当次 dump 其实失败了），按旧坐标点击自然打空。dump 后必须校验
文件是本次新生成的。

### 1.7 第四轮：CI 首次通过

`.github/workflows/ci.yml` 自加入以来 **8 次推送全部失败**，且都在 27s–1m 内结束 ——
从未跑到任何构建步骤。两处失败都在 workflow 配置，不在代码。

| | 失败点 | 根因 | 处置 |
| :-- | :--- | :--- | :--- |
| 1 | `Set up Android SDK` | `android-actions/setup-android@v3` 默认安装 `tools` 包，cmdline-tools 16.0 已移除它：`Failed to find package 'tools'` → sdkmanager 退出 1 | 传 `packages: ''` 跳过默认安装。本项目需要的 NDK 与 build-tools 已在下一步显式安装 |
| 2 | `Cargo clippy (mirage-core)` | CI 的 stable 解析到 **1.98.0**、本地是 **1.95.0**，1.98 新增 `chunks_exact_to_as_chunks`，命中 `handshake_cache.rs:409` 与 `udp.rs:614`。配合 `-D warnings`，**上游发版即 CI 红，我们一行代码没动** | 见下方工具链决定；两处 lint 也改用 `as_chunks::<2>()` 一并修掉 |

#### 工具链固定：单一来源 + 具体版本

第 4 批加的 `rust-toolchain.toml` 只钉了 **channel**，那不等于钉版本。现在：

- 根目录 `rust-toolchain.toml` 写死 `channel = "1.95.0"`，并声明自己的
  `components` 与 `targets`
- **删掉两个 crate 级副本**（`native/mirage-core/`、`native/mirage-jni/`）——
  rustup 就近优先，crate 级那两份会**静默覆盖**根目录的设置
- workflow 两处不再指定版本，改跑 `rustup show` 让文件说了算

升级 rustc 从此是一次显式改动：改这里的版本号 → 本地跑通 clippy/fmt/test → 提交。

#### JNI 门禁在真实 runner 上的首次输出

这道闸此前只在本地对好/坏产物双向验过。CI 实跑结果：

```
com.mirage.android.core.MirageNative.protectFd:(I)V        1
com.mirage.android.core.MirageNative.resolveConnectionOwner: 1
dex 内 MirageNative native 方法数                      43
.so 导出 Java_com_mirage 符号数                       43
ICoreService 类描述符                                  396
```

此前标为「只有首跑才知道」的三处 —— `cargo-ndk` 安装、
`$ANDROID_HOME/build-tools` 版本选取、`nm` 读 aarch64 `.so` —— 全部正常工作。

#### action 全量升级（`974da07`）

首绿那次六个 action 全部停在以 Node 20 为目标的大版本，而 runner 已强制 Node 24
——不影响当时通过，但会在某次 runner 升级后集体变红。逐个读过 release note 再升：

| action | 版本 | 影响本 workflow 的变化 |
| :--- | :--- | :--- |
| `actions/checkout` | v4 → v7 | v7 禁止在 `pull_request_target` / `workflow_run` 下检出 fork PR；本 workflow 只用 `pull_request` |
| `actions/setup-java` | v4 → v6 | 无（v5 = Node 24，v6 = ESM 重构，官方明示非用户可见破坏） |
| `android-actions/setup-android` | v3 → v4 | **有，见下** |
| `gradle/actions/setup-gradle` | v4 → **v5** | **刻意停在 v5，见下** |
| `actions/cache` | v4 → v6 | 无（Node 24 + ESM） |
| `actions/upload-artifact` | v4 → v7 | 无（v7 新增 `archive` 入参，不影响现用法） |

**setup-android v4 改了 cmdline-tools 的落地路径。** v4 装到
`cmdline-tools/<版本>`（默认 `20.0`），只有预装副本的 `Pkg.Revision` 相符时才用
`cmdline-tools/latest`。原先硬编码的
`$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager` 会扑空。该 action 会把
sdkmanager 实际所在目录 `addPath`，故改走 PATH —— 两个大版本下都成立。
v4 还在 action 内部把 `tools` 从 `packages` 里过滤掉了，首次那个失败不会再复现；
`packages: ''` 仍然保留，因为 v4 的默认包是 `platform-tools`，本项目不需要
adb/fastboot。

**setup-gradle 停在 v5 是决定，不是漏升。** `gradle/actions@v6` 把缓存逻辑抽成
闭源的 `gradle-actions-caching` 组件，**启用缓存即视为接受 Gradle 的商业条款**
（<https://gradle.com/legal/terms-of-use/>）。v5 仍是 MIT 且已跑 Node 24，能清掉
弃用又不牵涉授权。要升 v6 得先有人决定接受那份条款 —— 那是授权决策，不是维护动作。

实测结果：`run 35255691604` success **4m40s**（首绿是 6m42s），
**GitHub 侧注解数 0** —— 升级前那批弃用注解全部消失。

#### 顺带清掉 `ndk.dir`（`8a8e178`）

升级那次的日志里还有一条一直没人看的 AGP 告警：

```
[CXX5106] NDK was located by using ndk.dir property.
This method is deprecated and will be removed in a future release.
```

`app/build.gradle.kts:21` 本来就声明了 `ndkVersion = "26.3.11579264"`，AGP 会据此
去 `$ANDROID_HOME/ndk/<版本>` 找 —— 正是 sdkmanager 装进去的位置。workflow 里那行
`echo "ndk.dir=… >> android/local.properties"` 纯属冗余，而且**`ndk.dir` 优先级高于
`ndkVersion`**，AGP 实际看的一直是它，告警因此每次都报。删掉即可。

`ANDROID_NDK_HOME` 保留：cargo-ndk 不读 Gradle 的任何配置，必须单独给它环境变量。

复测 `run 35256942284` success，`NDK was located by using ndk.dir` 计数 0，
JNI 门禁 43 = 43。

#### NDK 版本收成单一来源，以及它顺带暴露的一个静默失败（`250e11d`）

版本号原本写在四处：`app/build.gradle.kts`、CI 的 sdkmanager 调用、CI 的
`ANDROID_NDK_HOME`、`scripts/build-android.sh` 两处。现在只在
`android/gradle/libs.versions.toml` 的 `[versions] ndk` 里：

```kotlin
ndkVersion = libs.versions.ndk.get()        // build.gradle.kts
```
```bash
NDK_VERSION=$(sed -n 's/^ndk = "\(.*\)"$/\1/p' android/gradle/libs.versions.toml)
```

**但光收单一来源不够。** 按本仓库的规矩做变异验证——把版本改成
`99.9.99999999` 在容器内跑 `assembleRelease`：

```
> Task :app:stripReleaseDebugSymbols
Unable to strip the following libraries, packaging them as they are: libmirage_jni.so.
BUILD SUCCESSFUL in 6m 40s
```

**AGP 找不到 `ndkVersion` 指定的 NDK 时不会失败**，只是跳过 strip 继续走。
版本写错的唯一症状是产物带着符号发出去，没有任何红灯。`sdkmanager` 同样靠不住：
未知包名只是告警，退出码仍是 0。

因此补了两道断言：
- 安装后检查 `$ANDROID_HOME/ndk/$NDK_VERSION` 目录存在
- `assembleRelease` 的输出里出现 `Unable to strip` 即失败

复测 `run 35301832360` success，日志确认版本取自 toml，无 `Unable to strip`。

> 这条值得单独记：**「构建成功」不等于「构建做了它该做的事」**。
> 本轮之前 `ndkVersion` 事实上是装饰性配置——本项目没有 `externalNativeBuild`，
> jniLibs 是预编译的，AGP 唯一用到 NDK 的地方就是 strip，而 strip 失败不致命。

### 全量验证结果汇总（容器内实测与实机）

第一轮完成时（`7b822f1`）：

```
testDebugUnitTest      14 passed
cargo clippy           0 (-D warnings)
cargo fmt --check      clean
cargo test --lib       131 passed
```

第二轮完成后（`be44e31`，本文撰写时实测）：

```
compileDebugKotlin     clean
testDebugUnitTest      27 passed  (CoreServiceState 10 / GeoIntegrityPolicy 6 / NodeStore 6 / PerAppFilter 5)
assembleRelease        BUILD SUCCESSFUL
JNI 门禁               退出码 0 (protectFd 1 · resolveConnectionOwner 1 · dex 43 = .so 43)
cargo clippy           0 (-D warnings)
cargo fmt --check      clean
cargo test --lib       131 passed
```

第四轮（`d7f628c`，GitHub Actions 首次绿灯，run 35252575218，6m42s）：

```
Rust (Format, Clippy & Tests)                    success 1m04s
Android (Build, Unit Tests & JNI Surface Gate)   success 6m38s
```

action 全量升级后（`974da07`，run 35255691604）：

```
整体                   success 4m40s   (升级前 6m42s)
GitHub 注解            0               (升级前: 一批 Node 20 / setup-java 弃用注解)
JNI 门禁               protectFd 1 · resolveConnectionOwner 1 · dex 43 = .so 43
```

> **实机验证状态**：第二轮的三个提交已于第三轮在 Sony SO-02K (Android 9 / API 28)
> 上补做真机回归，六项全部跑通，并因此查出 4 项新缺陷（见 §1.6）。
> 第一轮记录的 Galaxy S24+ (Android 16 / API 36) 结论仍来自第一轮执行者，未复现。

---

## 2. 后续建议与展望

四轮均已落地：计划内的第 0–4 批、第二轮多模型复审返工、第三轮真机回归返工、第四轮 CI 首跑。
**闭环范围**：静态审计 + 容器内构建与单元测试 + 变异验证 + Android 9 真机回归 + CI 绿灯。

按价值排序的后续项：

1. **高版本机型回归**。真机验证目前只覆盖 Android 9 (API 28)。Android 14+ 的
   前台服务类型规则与后台限制差异大（本项目在 API 29+ 才走 `startForeground(type)`
   分支，API 34+ 才用 `SPECIAL_USE`），建议在 API 34+ 机型上复跑 §3 的前两项。
2. **Geo 数据的真实性保证**。当前的 SHA-256 只能防截断、损坏、镜像不一致与单侧阻断，
   防不住控制了下载源的攻击者（摘要与产物同源）。要真正解决需要内置签名公钥
   （Ed25519 / minisign）校验摘要签名，前提是上游发布签名文件。跨源取摘要不是可行替代，
   原因见 §1.5 F5。
3. **`gradle/actions` v6 的授权决策**。v6 的缓存组件闭源且需接受 Gradle 商业条款，
   因此本仓库停在 v5（见 §1.7）。v5 不会永远维护，届时要么接受条款升 v6，
   要么关掉 setup-gradle 的缓存自己用 `actions/cache` 缓 `~/.gradle`。
4. **正式签名发布**：配置 `keystore.properties` 或 CI Secrets (`MIRAGE_KEYSTORE_*`)。
5. **Android Lint 尚未纳入门禁**。CI 目前只有 Kotlin 编译 + 单元测试 + R8 门禁，
   `./gradlew lint` 能发现清单与资源层面的问题，Kotlin 编译发现不了。
6. **多架构扩充**：当前默认仅编译 `arm64-v8a`，如需模拟器或 32 位设备支持，
   在 `build-android.sh` 与 `abiFilters` 中扩展 `x86_64` / `armeabi-v7a`。
7. **`nodes_json` 的 Intent 残留读取**（`CoreService.kt` 内 `intent.getStringExtra("nodes_json")`）
   现已是死代码（推送改走 AIDL `updateNodes`），保留为无害回退，可择机清理。


---

## 3. 已知遗留 / 待确认

1. **`native/mirage-core/src/tun/tcp.rs` 首包预读 `15ms → 60ms`**：
   已独立验证并提交（`f3274b1`）。
2. **release 包当前未签名**（`app-release-unsigned.apk`），因为未配置 keystore。
   这是设计行为。要出可安装包需按 README 配置 `keystore.properties` 或 `MIRAGE_KEYSTORE_*`。
3. **实机验证状态**：

   第二轮的三个提交已于第三轮在 **Sony SO-02K (Android 9 / API 28)** 上补做回归，
   六项全部通过（过程与查出的 4 项缺陷见 §1.6）：

   | 回归项 | 实测证据 |
   | :--- | :--- |
   | 连接 → 断开 → 等 5s 不得自己回来 | tun0 在 T=2s 归零并保持，内核启动次数 = 1 |
   | 快速连断连，节点列表不被旧快照覆盖 | 推送失败计数 0（修复前冷启动必失败） |
   | 内置 Geo 源更新 | `SHA-256 校验通过`，`last_update_verified=true` 落盘 |
   | 自定义源在自动路径应拒绝安装 | 日志逐条命中，无 `.dat` 落盘 |
   | 更新途中旋转不得双下载 | `已有 Geo 更新在进行中` × 2，tmp 未增殖 |
   | release 包连通性（R8 未打断 `protectFd`） | HTTP 204 穿透，tun0 增量 8856 字节，protect 失败 0 |

   第三轮的绑定改动另有三项实测：断开 + 退后台 → `:core` 降为 `cch-empty`；
   VPN 运行中退后台 → `adj=100` 且隧道存活；回前台 ~170ms 重新绑定且状态不闪断。

   仍未复现的：第一轮记录的 **Samsung Galaxy S24+ (Android 16 / API 36)** 结论。
   Android 14+ 的 FGS 类型规则与 Android 9 差异较大（本项目在 API 29+ 才走
   `startForeground(type)` 分支），建议在高版本机型上至少复跑前两项。
4. **`jniLibs/` 是 gitignore 的本地产物**。改了 `native/` 后必须
   `bash scripts/build-android.sh native`，否则 APK 里仍是旧 `.so`。
5. **`registerDefaultNetworkCallback` 看不见底层物理网络的消失**（2026-11-05 实测，
   详见 §Rust 侧「网络闸门的输入是陈旧的」）。隧道起来之后关掉 Wi-Fi + 移动数据，
   `onLost` 不送达，`currentPhysicalNetwork` 与内核的 `failover::network_state()`
   一起冻结在旧网络上。影响面不止故障切换的闸门：`setUnderlyingNetworks` 在断网期间
   也不会被撤下。修法是改成按 `NetworkRequest`（`NET_CAPABILITY_INTERNET` +
   `NET_CAPABILITY_NOT_VPN`）注册的 `registerNetworkCallback`，但它会动到
   `setUnderlyingNetworks` 的时序与暖池冲刷的触发点，须单独一轮并在两台真机上都验。
6. **节点 URI 在日志里的脱敏不够**。`[core] 开始启动内核 (uri=${uri.take(30)}...)` 与
   `onFailoverEvent` 的 `nodeUri.take(24)` 都会漏出 `mirage://` 之后的十几到二十几个字符，
   而那一段正是口令。core.log 是用户可导出分享的文件。应改成只保留 scheme + host:port
   （内核侧的 `redact_uri` 已经是这个形状，外壳侧两处尚未对齐）。

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

### 5.2 最低支持版本规则（Android 9 / API 28）

**项目要求最低支持 Android 9（API 28）安装与运行。** 当前 `minSdk = 26`，比该要求
低两级，有余量——但这条余量会被静默吃掉，所以立两道门禁。

#### 门禁一：`minSdk` 不得静默抬升（自动，在 CI）

风险不来自主动决策，来自依赖升级：AGP 与 AndroidX 版本一动，某些库会声明更高的
`minSdk`，**Gradle 取所有依赖的最大值合并，且不报错**。等发现时已经装不上老机器。

CI 在 `assembleRelease` 之后解包产物断言：

```bash
MIN=$(aapt2 dump badging "$APK" | sed -n "s/^minSdkVersion:'\([0-9]*\)'.*/\1/p")
[ -n "$MIN" ] && [ "$MIN" -le 28 ]   # 否则失败
```

注意取值行是 `minSdkVersion:`，**不是** `sdkVersion:`（后者不存在，写错会静默取空，
门禁变成永远通过——本仓库第一次写这条判据时就踩了这个，实测修正）。
解析为空必须判失败，不能当作通过。

#### 门禁二：每一阶段在两台真机上都验（人工，不可自动化）

**只在新机器上验过的改动不算验过。** 两台设备各自覆盖一端：

| 设备 | 版本 | 覆盖 |
| :--- | :--- | :--- |
| Sony SO-02K (`BH905W2A9G`) | Android 9 / API 28 | **最低支持线**；老硬件的性能实况 |
| Galaxy S24+ (`SM-S9260`) | Android 16 / API 36 | 新系统行为变更；折叠屏/大屏前置验证 |

引入 Compose 后这条尤其要紧：Compose 在 API 28 上**不是兼容问题而是性能问题**
（冷启动、首帧 jank、APK 体积约 +1.5–2 MB），2018 年的 SO-02K 才是真实下限，
在 S24+ 上测不出来。

#### 配套：API 门槛用法的强制回退

设计方案里若干 API 高于 28，在 Android 9 上必须有回退而不是静默失效：

| API | 最低级别 | Android 9 上的正确做法 |
| :--- | :--- | :--- |
| `HapticFeedbackConstants.CONFIRM` | 30 | 回退 `VIRTUAL_KEY` / `KEYBOARD_TAP`（API 1） |
| `HapticFeedbackConstants.SEGMENT_TICK` | 34 | 同上 |
| `OnBackAnimationCallback` | 34 | **禁止直接引用平台类**，统一走 `androidx.activity` 的 `OnBackPressedDispatcher`（自带向下兼容，API 28 退化为普通返回） |
| `FoldingFeature` | 库级兼容 | API 28 上返回空集，走单栏分支即可 |

触感常量不会崩（`performHapticFeedback` 遇未知常量返回 `false`），但会让 Android 9
用户完全没有触感反馈——多模态反馈少一条腿，属于静默降级，必须显式回退。

## 6. 栈式 PR 的合并规程（2026-09-24 实测）

### 6.1 背景

本轮 UI 工作形成了四层线性 PR 栈，每层的 base 都是上一层的分支：

```
#11 ui/fluid-foundation -> main
#12 ui/sheet-behavior   -> ui/fluid-foundation
#13 ui/rules-chrome     -> ui/sheet-behavior
#14 ui/node-text-spring -> ui/rules-chrome
```

### 6.2 规程一：栈式 PR 必须用合并提交，不能 squash

仓库历史用的是 squash（main 上早期提交标题带 `(#9)`、`(#10)`）。但 squash 会为 main 生成一个全新提交，子分支并不以它为祖先——接着合子 PR 时 git 会尝试把父 PR 的改动在同一批文件上重新铺一遍，冲突风险高。

本轮统一用 `gh pr merge <n> --merge`，四次合并全部一次通过，事后 `git merge-base --is-ancestor` 确认四个提交（`a0eed2c`、`d545ddc`、`295e302`、`1e198b8`）都在 main 上，祖先关系完整。

### 6.3 规程二：合并栈式 PR 时不要用 `--delete-branch`，会杀掉子 PR

本轮实际发生：执行 `gh pr merge 11 --merge --delete-branch` 后，GitHub 删除了 `ui/fluid-foundation`，但**没有**把子 PR #12 改指 main，而是因其 base 分支不存在直接把 #12 转为 `CLOSED`。

随后陷入死锁：
- 关闭状态不能改 base（`Cannot change the base branch of a closed pull request`）
- base 分支已删则不能 reopen（`Could not open the pull request`）
- 两条路互堵

**恢复办法（已验证可行）**：把被删的 base 分支从 main 历史里推回原位，再 reopen、再改指：

```bash
git push origin <被合并的提交>:refs/heads/<被删的分支名>
gh pr reopen <子PR编号>
gh pr edit <子PR编号> --base main
```

**正确顺序**：
1. 先把**所有**子 PR 改指 main：`gh pr edit <n> --base main`（在它们还是 OPEN、且各自 base 分支还存在时做）
2. 再自底向上逐个合并，**不带** `--delete-branch`：`gh pr merge <n> --merge`
3. 全部合完、确认所有 PR 均为 MERGED 且无在途子 PR 之后，再统一删除远端分支

### 6.4 附带事实：非 main 基线分支的 CI 触发规则

CI 工作流的 `on.pull_request.branches` 与 `on.push.branches` 都只列了 `[main, master]`，过滤的是 **base** 分支，所以栈中 base 不是 main 的 PR 不会自动触发 CI。

本轮的做法是对栈顶分支手动触发 CI：

```bash
gh workflow run ci.yml --ref <栈顶分支>
```

栈顶包含全部改动，它绿即代表整栈内容绿。
