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
| 故障切换看门狗 | `CoreService.kt:729-840` | ~118 行 |
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

### P3 — 外壳拆除

删除 `startFailoverWatchdog` / `doFailover` / `failoverRestartJob`（~118 行）。
`CoreService` 只保留：生命周期、`NetworkCallback` 注册与转发、响应「请求重启」事件。
`VpnRepository` 的 `pushNodes` 改调 `setNodeList`。

---

## 5. 风险与前置条件

1. **双重决策期必须避免**。P2 完成而 P3 未做时，壳与内核会同时跑看门狗。必须用开关保证
   内核策略上线即关闭壳侧，**不得并存**。
2. **节点清单同步**。用户增删节点后内核须及时获知，否则会切到已删除的节点。
   *（2026-11-03 已处理）* `updateNodes` 的下发只在原生库已装载时发生，而 AIDL 可能在任何一次
   `start` 之前到达，那时下发会被守卫短路。因此 `startInternal` 在注入块里用
   `NodeStore.getNodesJson()` 补发一次全表——持久化的 prefs 才是权威来源，
   与 `RuleStore.toJson()` 的自主注入同一模式。缺这一步内核会永远「无可切换节点」。
3. **与协议断代叠加**。v0.15 刚断代（`befd125`），故障切换正是认证失败高发路径，改动会与之交叠。
   必须配 v0.15 服务端实测，不能只看健康位返回值。
4. **验证手段尚未解决**。故障切换需要构造「节点不可达」，比既往任何一轮都难复现。
   **动工前必须先确定造故障的方法**（候选：错误端口的假节点、防火墙规则），否则会重演
   「代码改了但没验证到」。此项为 P2 的前置条件，不是事后补充。

---

## 6. 不在本方案内

- 重构 APK：内核换代被 43 函数接口面完全隔离，UI 与数据层无需改动（见 §1）
- 搬动 (A) 类能力：平台强制，搬不走（见 §2.1）
- 绕开 `VpnService`：Android 上不存在该路径（见 §1.1）
