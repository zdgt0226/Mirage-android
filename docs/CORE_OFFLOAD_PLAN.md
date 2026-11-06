# 网络策略下沉方案（2026-11-03）

## 0. 这份文档解决什么

用户提出的目标架构：**mirage-rs 内核负责全部网络功能，Android APK 退化为外壳**，只做状态监测、节点管理、规则调整，TUN 直连内核。

本文档先给出**现状实测**（这个架构多大程度上已经存在），再给出**剩余差距的下沉方案**。

---

## 1. 现状：目标架构已基本实现

`CoreService.kt:534-545` 用 `VpnService.Builder().establish()` 建 TUN，把裸 fd 交给内核：

```kotlin
external fun start(tunFd: Int, uri: String, poolSize: Int, mtu: Int): Int
```

此后收发包、握手、PFS、连接池、DNS 分流、规则匹配全在 Rust 内，Kotlin 不碰任何字节。

**佐证**：内核同步至 mirage-rs v0.15.0（`befd125`，协议断代）改动 5831 行，全部落在
`native/mirage-core/**`，**未触及** `mirage-jni/src/`、任何 `.kt`、`.aidl`、资源；
`MirageNative.kt` 的 43 个 `external fun` 自 `abd23d5` 未变。v0.15 合入后三次 main CI
（`ff213da` / `f851084` / `fb0a27b`）全绿，含「JNI surface survived R8」门禁。

**Rust 导出 43 : Kotlin 声明 43，差集为空**——接口面既无断裂，也无未接入的新能力。

### 1.1 一条平台硬约束

**"TUN 直连内核"在 Android 上无法按字面实现。** `VpnService.Builder.establish()` 是框架 API，
需要应用上下文与 VPN 权限，Rust 侧无法自建接口。可达的上限是**应用建 fd、移交内核**，即现状。
任何绕开 VpnService 的设想在 Android 上不存在，不要在此方向投入。

---

## 2. 实测：外壳里还剩哪些网络逻辑

分两类。

### 2.1 (A) 平台强制，搬不走

| 能力 | 出现次数 | 为什么搬不走 |
| --- | --- | --- |
| `VpnService.Builder` 路由 / DNS / 分应用代理 | 12 | 框架 API，须应用上下文 |
| `protect()` | 2 | `VpnService` 实例方法 |
| `getConnectionOwnerUid`（应用归属） | 4 | `ConnectivityManager` API |

### 2.2 (B) 可下沉，且与"外壳"定位冲突

| 组成 | 位置 | 规模 |
| --- | --- | --- |
| ~~故障切换看门狗~~ | ~~`CoreService.kt:729-840`~~ | ~~118 行~~ —— P3 已删除（实删 103 行） |
| 物理网络跟踪 | `CoreService.kt:651-700` | ~50 行（注册须留壳，判定可下沉） |
| 配置传递 | `CoreService.kt:296-319`、`VpnRepository.kt:373,387,403` | ~25 行 |
| 字段定义 | `CoreService.kt:123-124` | 2 行 |

**看门狗当前行为**：
- 轮询间隔 `checkIntervalSec`（默认 15s）+ `failoverBackoffSec`，调用 `isRunning()` / `isHealthy()`
- 判定不健康后进入 `doFailover()`：
  - `"best"` 模式：`Semaphore(4)` 并发对全部节点 `testNode(uri, 3000)` 测 RTT 后排序
  - 否则：按当前节点索引轮转
- 选中后：可热切则 `setNode(uri)`；否则 `stop()` + 3 秒后 `startInternal()`（`failoverRestartJob`）

**内核侧已有**：`engine.rs:146 is_healthy()`、`pool.rs:242 is_healthy()`、`testNode`、`setNode`、
认证失败指数退避与暂停补池（`8333bf9`）。
**内核侧缺少**：节点清单、选路策略、重启决策、网络变化感知。

即能力在内核、策略在外壳，两边可能各自决策——我在状态位诊断中撞见的
`failoverRestartJob` 与 `stopInternal` 竞态即此结构的产物。

### 2.3 两项降低成本的既有条件（已查证）

1. **JNI 反向回调通道已存在**：`native/mirage-jni/src/lib.rs:254-302` 已有 `JavaVM` 与
   `attach_current_thread_as_daemon`（应用归属 `abd23d5` 时建立）。内核主动通知 Kotlin 不是从零开始。
2. **重启未必需要重建 TUN**：`CoreService.kt:451-453` 中 fd 仍有效即复用。故障切换走重启时，
   多数情况下壳无需重新 `establish()`。

---

## 3. 硬边界：重启的判定可下沉，执行不可

内核无法创建 TUN fd。因此：

- **可完全下沉**：健康判定、节点选优、热切换（`setNode` 不需要新 fd）
- **不可完全下沉**：涉及重建 TUN 的重启——内核只能发出「请求重启」事件，由壳决定复用 fd 还是重建

方案按此边界切分，不要试图绕开。

---

## 4. 下沉方案

### P1 — 接口面（唯一改动 JNI 的一段）

新增 3 个下行函数，接口面 43 → 46：

| 函数 | 用途 |
| --- | --- |
| `setNodeList(json): Boolean` | 节点全表交给内核，使其具备自主选路所需的清单 |
| `setFailoverPolicy(json): Boolean` | mode / interval / backoff / 重启延迟，一次性下发 |
| `onNetworkChanged(netId: Long, hasInternet: Boolean)` | 壳转发 `ConnectivityManager.NetworkCallback` 事件 |

上行复用既有 `JavaVM` 通道新增一个事件：
`onFailoverEvent(kind: Int, nodeUri: String)`，`kind ∈ {已热切换, 请求重启}`。

**验收**：
- `JNI surface survived R8` 门禁绿
- Rust 导出与 Kotlin 声明**计数相等且差集为空**（当前 43:43，P1 后应为 46:46）
- 新增函数在 `catch_unwind` 保护内（沿用 `7b822f1` 建立的 FFI 约定）

### P2 — 内核实现

在 Rust 内实现看门狗循环、选路、热切换；重启只发事件不执行。
复用 `is_healthy()` 与 `pool.rs` 既有的认证退避节奏，**不要再写一套独立节拍**，否则与 `8333bf9`
的退避互相叠加。

### P3 — 外壳拆除（2026-11-05 已完成）

> **前置修复（2026-11-05 已完成）**：内核看门狗读的引擎句柄必须是 `stack.engine()`。
> 原先 `RunState` 存了 `Arc<Engine>` 快照，节点热切换后它指向已 `shutdown()` 的死引擎，
> `isHealthy()` 恒为 false。该字段已删除，4 处读取全部改走 `stack.engine()`。
> 不先修这条，P3 等于把外壳的误报原样搬进内核。详见 `AUDIT_HANDOFF.md`
> 「引擎句柄只能取 `stack.engine()`」。

已删除 `startFailoverWatchdog` / `doFailover` / `failoverRestartJob` / `watchdogJob`（103 行）。
`CoreService` 只保留：生命周期、`NetworkCallback` 注册与转发、响应「请求重启」事件。

**一处原方案写错，未执行**：本节原写「`VpnRepository` 的 `pushNodes` 改调 `setNodeList`」。
不能改。`pushNodesToCore` 在 **UI 进程**，原生库只装在 `:core`，直接调 JNI 必抛
`UnsatisfiedLinkError`。正确路径 UI → AIDL `updateNodes` → `:core` 内
`MirageNative.setNodeList` 自 P1 起已经通了（`CoreService.kt` 的 `updateNodes` 实现），
无需改动。

**外壳删除后必须在内核补齐的两条行为**（否则不是下沉而是功能丢失）：

| 外壳原有 | 补齐方式 |
| --- | --- |
| `if (currentPhysicalNetwork == null) continue` | 看门狗读 `failover::network_state().1`，`false` 时跳过整轮并把连续失败计数归零。见下方「网络闸门的输入目前是坏的」 |
| `failoverBackoffSec` 每轮失败 `+5s`、上限 `30s` | `failover::escalate_backoff()`（纯函数 + 单测）。看门狗自己累加 `dynamic_backoff_sec`，与外壳下发的静态 `policy.backoff_sec` **相加**。外壳固定下发 `backoff_sec = 0`，否则两处退避叠加 |

**退避的作用域比字面更窄，这是刻意与外壳对齐。** `dynamic_backoff_sec` 是看门狗任务的局部
变量，而 `start()` 每次都 `spawn_failover_watchdog()` 并靠 `WATCHDOG_GEN` 让旧实例退出
（真机实测可见 `[failover] 看门狗 gen=6 已被接替, 退出`）。所以「请求重启」一旦落地，
退避就随新看门狗归零 —— 实测三轮重启间隔恒为 37s（2×15s 巡检 + 3s `restart_delay_ms`
+ 约 1.2s 重启），而不是 37/42/47s 递增。外壳同理：`failoverBackoffSec` 活在
`startFailoverWatchdog` 的闭包里，而 `startInternal` 开头的 `cancelPeriodicJobs()`
会取消 `watchdogJob`、由 `startLocked` 重起一个。两边行为一致，**不是**下沉引入的回归。
退避真正生效的只有「同一个看门狗生命周期内连续失败」这一条路径，即热切换本身反复失败
（`Engine::new` 报错）而没有触发重启。高频空转的防线主要来自重启路径自带的
3s + 两个巡检周期，而不是退避。

#### 网络闸门的输入目前是坏的（2026-11-05 实测，P3 之前就存在）

`failover::network_state().1` 的唯一写入方是外壳的 `switchTo()` → `onNetworkChanged`。
实测 `switchTo(null)` **不会**在物理网络消失时被调用：

| 时刻（本地） | 事件 |
| --- | --- |
| 09:01:03 | `svc wifi disable` + `svc data disable`，`dumpsys connectivity` 报 `Active default network: none` |
| 09:01:10 / 09:01:47 / 09:02:24 | 内核照旧判不健康、向外壳要重启三次 —— 闸门没拦住 |
| — | `core.log` 与 logcat 全程**没有** `底层物理网络切换: 400 -> null` |
| 09:04:16 | `svc wifi enable` 后出现 `底层物理网络切换: 400 -> 409` —— 回调活着，但 `currentPhysicalNetwork` 整段时间一直冻结在 400 |

机制：`CoreService` 注册的是 `cm.registerDefaultNetworkCallback(cb)`，它只跟踪**默认
网络**的生命周期。隧道建立后底层物理网络不再是被跟踪的那一个，它的 `onLost` 不会送达，
`onCapabilitiesChanged` 也不会对它触发，于是 `currentPhysicalNetwork` 保持陈旧。

**这不是 P3 引入的。** 外壳原看门狗的 `if (currentPhysicalNetwork == null) continue` 读的是
同一个陈旧字段，在它被写出来针对的那个场景里同样是死代码。下沉后行为等价，闸门的代码
路径与单测都已就位，但要让它真的生效，必须先修回调注册 —— 改成按 `NetworkRequest`
（`NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_NOT_VPN`）注册的 `registerNetworkCallback`。
那会同时改动 `setUnderlyingNetworks` 的时序与暖池冲刷的触发点，需要自己一轮真机矩阵，
不并入 P3。

**另两处 P3 自带的坑**：

1. **运行时设置会凝固**。`setAutoReconnect` / `setCheckInterval` / `setFailoverMode`
   三个 AIDL setter 原先只改 `serviceConfig`，由外壳看门狗每轮实时读取。下沉后内核拿的是
   启动时快照，三者必须各自调 `pushFailoverPolicy()` 重推，否则表现为「改了检查间隔要重连
   才生效」「关掉自动重连后内核仍自己换节点」。
2. **`serviceConfig.uri` 会过时**。内核热切换只改自己的 `CURRENT_NODE_URI`，而
   `startLocked` 在没有 `uriOverride` 时正是取 `serviceConfig.uri` 建连。不在
   `EVENT_SWITCHED` 里同步，下一次重启就会回到刚被判死的节点。

#### 真机实测（SO-02K / Android 9，两个本地上游服务端 8443 + 9443，2026-11-05）

策略确实下发且开关为真：

```
[core] 切换策略下发内核: ok=true policy={"mode":"best","interval_sec":15,
       "backoff_sec":0,"restart_delay_ms":3000,"enabled":true}
```

**场景一 · 热切换（杀掉当前节点 A）**

| 时刻（UTC） | 事件 |
| --- | --- |
| 00:54:14 | `kill` 服务端 A（设备当前节点） |
| 00:54:25 | `connect <本地服务端>:8443: Connection refused`（地址按 AGENTS §4 隐去） |
| 00:54:20 | `[failover] 内核判定连接异常 (第 1 次)`（该行已被内核日志环形缓冲挤出，由 `第 2 次` 反推） |
| 00:54:34.969 | `[failover] 内核判定连接异常 (第 2 次)` |
| 00:54:35.051 | `[TUN] 引擎已热切换 (旧引擎后台协程已安全终止)` + `[failover] 内核已热切换` —— 判定到切换 **82ms** |
| 00:54:35.052 | 外壳 `[core] 故障切换事件: 已热切换` |
| — | `selected_index` 由 `0` 落盘为 `1`（经 `ICoreCallback` 单向回流，`:core` 未直写 prefs） |
| — | 服务端 B 开始中继真实目标（281 行新日志，含 `play.googleapis.com:443` / `mtalk.google.com:5228`） |
| 00:54:35 → 00:56:51 | **零**后续 `[failover]` 行，`内核已启动` 计数仍为 1，无 `请求重启`、无误报 —— 这正是 PR #26 修死引擎句柄之前必然出现误报的窗口 |

**场景二 · 请求重启（两个服务端全杀）**

三轮 `候选节点全部不可达 → 请求外壳重启 → 外壳重启`，`:core` 进程 pid 17309 全程不变：

```
[failover] 候选节点全部不可达, 请求外壳重启 (退避 5s)
[core] 故障切换事件: 请求重启
[core] 按内核请求重启隧道 (保持 TUN)
[core] 复用现有 TUN 描述符 (fd=48)，保持 VPN 接口存活
[core] 内核已启动
```

两点关键结论：**(1)** 没有 tokio panic、没有 `:core` 死亡 —— 证实
`dispatchFailoverEvent` 转线程是必要且足够的（这条路径会在事件回调里同步走到
`MirageNative.stop()`）；**(2)** 重启复用既有 fd，断网窗口内不存在明文外发。

服务端恢复后设备自愈回 B 并恢复真实中继，`[failover]` 归于静默。

**开关映射**：`FailoverPolicy.enabled` 取「断线自动重连」开关。外壳原看门狗在
`autoReconnect` 为 false 时只记日志不动手，内核跳过整轮与之等价。

**事件处理必须异步**。`onFailoverEvent` 的调用链是 内核看门狗任务 → `failover::emit_event`
→ JNI 上行，全程跑在 tokio worker 线程上。若在该线程上同步走到 `MirageNative.stop()`，
Rust 侧 take 走 `RUNTIME` 并 drop `Arc<Runtime>`；一旦那是最后一个引用，就等于在 runtime
自己的 worker 线程里 drop runtime —— tokio 直接 panic，且 panic 在 spawn 的任务里，
`jni_boundary!` 捕不到，表现为内核静默失去看门狗。故 `dispatchFailoverEvent` 一律
`scope.launch` 转线程。

**无声失败的防线**。Rust 侧 `FailoverPolicy` 每个字段都带 `#[serde(default)]`，键名拼错会被
静默丢弃并回落到 `enabled = false` —— 外壳看门狗已删除，结果是**无人**做故障切换，
日志里却只有一行 `切换策略下发内核: ok=true`。策略 JSON 的构造已抽成纯对象
`CoreService.FailoverControl`，由 `CoreServiceStateTest` 逐字段断言键名。

---

## 5. 风险与前置条件

1. **双重决策期必须避免**。*（2026-11-05 已结束）* P2 完成而 P3 未做时，壳与内核会同时跑
   看门狗。期间靠 `FailoverPolicy.enabled` 默认 `false` 保证内核只观察不动手。P3 拆除外壳
   看门狗后该位由外壳下发，默认 `false` 保留为缺省安全值：未下发策略时无从判断外壳是哪一代。
2. **节点清单同步**。用户增删节点后内核须及时获知，否则会切到已删除的节点。
   *（2026-11-03 已处理）* `updateNodes` 的下发只在原生库已装载时发生，而 AIDL 可能在任何一次
   `start` 之前到达，那时下发会被守卫短路。因此 `startInternal` 在注入块里用
   `NodeStore.getNodesJson()` 补发一次全表——持久化的 prefs 才是权威来源，
   与 `RuleStore.toJson()` 的自主注入同一模式。缺这一步内核会永远「无可切换节点」。
3. **与协议断代叠加**。v0.15 刚断代（`befd125`），故障切换正是认证失败高发路径，改动会与之交叠。
   必须配 v0.15 服务端实测，不能只看健康位返回值。
   断代的现场特征（2026-11-05 实测，供排查时对号）：客户端 ≥ v0.15 对上 ≤ v0.14 服务端时，
   服务端认证不过会把连接转给伪装站，客户端用自己的会话密钥解不开其真实 TLS 响应，
   日志为 `TIME_SYNC 接收/解密失败: early eof`（`proxy/pool.rs` 的 recv 失败分支）。
   若伪装 TLS 握手本身就没走完（`Timeout before CCS`），那是链路层问题而**不是**版本断代，
   两者不可混为一谈。
4. **验证手段**。*（2026-11-05 已解决）* 故障切换需要构造「节点不可达」。
   办法是在宿主机上跑**两个本地 Mirage 服务端**（上游 `mirage server -c <cfg>`，监听不同端口），
   设备经 LAN 连它们，`kill` 掉当前实例即得到一个真实、可重复、可随时恢复的故障。
   这比改防火墙或假端口更接近真实路径：伪装 TLS 握手、认证、TIME_SYNC 帧全都真的走一遍。
   注意服务端两端协议必须同代（见风险 3）；本地自编上游 HEAD 可直接满足。
   配置含口令，只能放在仓库外的临时目录，**不得入库**（AGENTS §4）。

---

## 6. 不在本方案内

- 重构 APK：内核换代被 43 函数接口面完全隔离，UI 与数据层无需改动（见 §1）
- 搬动 (A) 类能力：平台强制，搬不走（见 §2.1）
- 绕开 `VpnService`：Android 上不存在该路径（见 §1.1）
