# SPEC: 分应用代理 (Per-App Proxy) 与 自适应连接池省电优化 (Adaptive WarmPool)

- **状态**: 等待人类批准 (Awaiting Human Approval)
- **版本**: v1.0.0
- **目标仓库**: `/opt/Mirage-android`
- **校准等级**: **Tier 2 (Normal - Feature & Power Optimization)**

---

## 1. 业务背景与问题定义

1. **痛点 1 (应用分流)**: 用户在使用代理时，国内银行、政务、办公（钉钉/企微）与微信经常因为 IP 变更触发风控或异地登录告警；用户需要精确控制哪些 App 走代理（白名单模式），或哪些 App 强制绕过代理（黑名单模式）。
2. **痛点 2 (过夜/后台省电)**: 手机在息屏或无流量时，固定大小（16~64）的预热连接池会在后台持续进行 TLS 握手与超时重建，频繁唤醒移动蜂窝基站 RRC 状态，产生不必要的电量消耗与无线电开销。

---

## 2. 可执行验收场景 (Acceptance Criteria / Gherkin)

### 场景 1: 分应用代理黑名单模式 (Disallow Mode / 绕过模式)
```gherkin
Given 分应用代理开关处于开启状态
And 代理模式设置为 "绕过所选应用 (Disallow Mode)"
And 用户勾选了 "com.tencent.mm" (微信) 与 "com.eg.android.AlipayGphone" (支付宝)
When 用户点击连接启动 VPN
Then VpnService.Builder 必须调用 addDisallowedApplication("com.tencent.mm")
And VpnService.Builder 必须调用 addDisallowedApplication("com.eg.android.AlipayGphone")
And 自身包名 "com.mirage.android" 必须始终被自动排除在 VPN 之外 (防止自环)
And 微信与支付宝的网络流量将直接通过物理网络直连，不进入 TUN 网卡
```

### 场景 2: 分应用代理白名单模式 (Allow Mode / 仅代理模式)
```gherkin
Given 分应用代理开关处于开启状态
And 代理模式设置为 "仅代理所选应用 (Allow Mode)"
And 用户勾选了 "com.android.chrome" 与 "org.telegram.messenger"
When 用户点击连接启动 VPN
Then VpnService.Builder 必须调用 addAllowedApplication("com.android.chrome")
And VpnService.Builder 必须调用 addAllowedApplication("org.telegram.messenger")
And 自身包名 "com.mirage.android" 严禁被加入允许列表 (防止自环)
And 仅 Chrome 与 Telegram 的流量会被路由进 TUN 网卡，其余未勾选 App 流量完全走原生网络
```

### 场景 3: 分应用代理未启用 (默认全局代理)
```gherkin
Given 分应用代理开关处于关闭状态 (默认)
When 用户点击连接启动 VPN
Then VpnService.Builder 不调用 addAllowedApplication 亦不调用 addDisallowedApplication (自身除外)
And 维持现有全局路由接管与分流规则
```

### 场景 4: 无效/已卸载包名的容错与鲁棒性
```gherkin
Given 用户配置的已勾选包名列表中包含一个已卸载的无效包名 "com.nonexistent.app"
When 用户启动 VPN
Then CoreService 必须通过 runCatching / PackageManager 校验过滤掉无效包名
And 绝不抛出 PackageManager.NameNotFoundException 或导致 VPN 启动失败
```

### 场景 5: 息屏与低功耗自适应连接池 (Adaptive WarmPool)
```gherkin
Given VPN 处于连接保护状态，且默认连接池大小为 16
When 系统广播触发 Intent.ACTION_SCREEN_OFF (屏幕熄灭休眠)
And 且前台无活跃高吞吐数据流
Then 客户端控制器自动调用 MirageNative.setPoolSize(4)
And 内核将预热池目标容量缩容至 4 条，停止多余的后台连接建立
When 系统广播触发 Intent.ACTION_SCREEN_ON (屏幕点亮)
Then 客户端控制器自动调用 MirageNative.setPoolSize(16)
And 内核在 1 秒内平稳恢复至完整预热池容量
```

---

## 3. 负面约束与不变量 (Invariants - 严禁破坏的事项)

1. **严禁破坏现有规则分流系统**: 分应用代理属于第 0 层（系统级网卡分流），进入 TUN 网卡的流量仍需完全兼容既有的 Fake-IP、CN 白名单直连与 GeoIP/GeoSite 规则判定。
2. **严禁在主线程加载 App 列表**: 手机已安装应用可能达数百个，应用列表与图标必须在 `Dispatchers.IO` 异步协程中加载，并支持按应用名/包名关键词实时搜索过滤，保证 120Hz 界面丝滑流畅。
3. **零新第三方依赖引入**: 完全基于 Android 系统标准 `PackageManager`、`VpnService.Builder` 与 Kotlin Flow，严禁引入未经批准的重型外部库。
4. **自身包名永久防环**: 无论白名单模式还是黑名单模式，`com.mirage.android` 必须始终被自动保护/排除。

---

## 4. 实施与配置计划 (Setup Plan)

- **工作区隔离**: 保持在当前 `/opt/Mirage-android` 工作区。
- **涉及新增与修改文件**:
  1. `android/app/src/main/java/com/mirage/android/data/model/AppInfo.kt` (新建 - App 实体数据类)
  2. `android/app/src/main/java/com/mirage/android/data/repository/AppListRepository.kt` (新建 - 异步扫描与缓存已安装应用)
  3. `android/app/src/main/java/com/mirage/android/ui/AppFilterActivity.kt` (新建 - 分应用代理选择与搜索界面)
  4. `android/app/src/main/java/com/mirage/android/ui/adapter/AppFilterAdapter.kt` (新建 - 列表适配器)
  5. `android/app/src/main/java/com/mirage/android/CoreService.kt` (修改 - 接入 `addAllowedApplication` / `addDisallowedApplication` 与屏幕亮灭广播)
  6. `android/app/src/main/res/layout/activity_app_filter.xml` (新建 - Material 3 界面布局)
- **Gauntlet 验证计划**:
  - 单元测试：`AppListRepositoryTest`、`PerAppFilterTest`、`WarmPoolAdaptiveTest`；
  - 实机测试：在 `BH905W2A9G` 和 `R5CX21FD9PX` 上进行真机安装与白/黑名单分流实测。
