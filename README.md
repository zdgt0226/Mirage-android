# Mirage-Android

基于 [Mirage-rs](https://github.com/zdgt0226/Mirage-rs) 协议的高性能 **Android 客户端** —— 移动端轻量裁剪内核 (`mirage-core`, Rust) + 独立内核守护进程 (`:core`) + TUN 用户态全流量代理 + 现代化 Material 3 界面。

```
┌─────────────────────────────────────────────────────────────────┐
│ App 进程 (Kotlin UI)                                            │
│  首页 / 节点管理 / 复合规则 / 监控诊断 / TUN调优 + CoreController │
├───────────────────────────────┬─────────────────────────────────┤
│ AIDL Binder (死亡代理/双向通信) │ 跨进程控制、实时状态与流量统计  │
├───────────────────────────────▼─────────────────────────────────┤
│ :core 独立进程 (CoreService: VpnService + JNI)                  │
│  TUN 设备管理 + Rust 用户态协议栈 (mirage-core)                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## ✨ 核心特性

### 1. 协议内核与传输引擎 (mirage-core, Rust)
- **Mirage 隧道协议**：TLS 1.3 ClientHello 字节级仿真 / ChaCha20-Poly1305 分帧 / 前向安全 (PFS) / 密码学混淆。
- **TUN 用户态协议栈**：基于 `smoltcp` 深度定制的高并发 TUN 引擎，支持 TCP + UDP 双协议栈透明代理。
- **预热连接池与并发拨号**：WarmPool 预热连接池 + on-demand 并发拨号（信号量限流控制）+ 隧道空闲回收与连接回流。
- **UDP 多路复用 (UDP Mux)**：支持将高频无连接 UDP 数据包复用进可靠加密隧道传输。
- **16KB 内存分页对齐**：原生 arm64-v8a 动态库完全兼容 Android 15/16 16KB Page Size 要求。

### 2. DNS 引擎与全方位防泄漏 (Zero DNS & IPv6 Leak)
- **Fake-IP 虚拟映射引擎 (RFC 6890)**：海外与代理域名在本地 0ms 返回 `198.18.0.0/15` 虚拟地址，**不向任何本地运营商/物理网络发送明文 DNS 请求**，远端海外代理节点安全递归解析，彻底消除 DNS 泄漏与污染。
- **Anycast DNS 全端口透明劫持**：拦截进入 TUN 的**所有 53 端口 UDP/TCP 查询**（包括硬编码的 `8.8.8.8`、`1.1.1.1`、`114.114.114.114`），自动伪源原路应答，杜绝顽固 App 旁路穿透。
- **国内 DNS 双上游并发竞速 (DNS Race)**：直连域名同时向主 DNS（阿里 `223.5.5.5`）与备用 DNS（腾讯 `119.29.29.29`）并发请求，首个有效响应即刻返回，国内域名解析耗时降至 10~30ms。
- **IPv6 路由接管与防旁路泄漏 (Anti-IPv6 Leak)**：动态注入 `::/0` 路由与 `fdfe:dcba:9876::1/128` 地址，配合 ICMPv6 Destination Unreachable 端口不可达回包，防止 5G 蜂窝网络 IPv6 绕过 VPN 导致 Google 全家桶断线。
- **全量 DNS 结构化实时日志**：在监控面板实时输出查询来源、分流动作、上游解析耗时（ms）与 Fake-IP 分配详情。

### 3. 多级分流与动态 GeoData 规则引擎
- **动态 GeoSite / GeoIP 引擎**：支持自定义配置与一键在线更新 `geosite.dat` 和 `geoip.dat` 规则库，支持版本管理、体积监控与更新失败安全回退。
- **四级路由判定机制**：
  1. 用户自定义复合规则（支持 `AND` / `OR` 逻辑，包含 Exact / Suffix / Keyword / Regex / IP-CIDR / Port / Protocol）；
  2. 动态 GeoSite (CN) / GeoIP (CN) 数据库匹配；
  3. 内置 7730 条中国 IPv4 网段（**`O(log N)` 二分查找算法**，10ns 级快速匹配）+ CN 常用域名白名单；
  4. 默认策略（直连 / 代理 / 拦截）。
- **屏蔽海外 QUIC (UDP 443)**：对命中代理的 UDP 443 请求即时回送 ICMP Port Unreachable，促使 YouTube、X (Twitter)、Chrome 快速回退至 HTTP/2 极速连接。

### 4. TUN 性能与移动端高级网络调优
- **自定义 TUN MTU**：支持 1400（蜂窝网络推荐，预留 GTP/TLS 头部防分片卡顿）、1420（Wi-Fi/宽带推荐）、1500（标准以太网），以及 1280~1500 范围自由调节。
- **TCP 隧道空闲超时 (RELAY_IDLE)**：支持 120s（极速释放）、300s（标准推荐）、600s（长连接保持），平衡 IM 推送与僵尸连接 FD 占用。
- **TUN 批处理队列深度 (Batch Size)**：支持 16 包（低延迟）、32 包（推荐平衡）、64 包（极限吞吐量）调度。
- **主页状态实时摘要**：主页卡片动态显示当前调优参数（如 `MTU: 1400 · 批处理: 32 · IPv6接管 · 屏蔽QUIC · UDP Mux`）。

### 5. 现代化应用控制与系统集成 (Kotlin / Material 3)
- **独立进程架构 (`:core`)**：UI 主进程与 VPN 内核进程完全解耦，Binder 死亡代理监听与自动重连，UI 退出或异常不影响后台代理稳定运行。
- **节点管理与智能优选**：支持 `mirage://` 标准订阅链接解析与手动编辑，支持批量并发测速（RTT 毫秒级排序）、自动优选节点与运行时无缝热切换（`arc-swap`）。
- **监控与诊断系统**：
  - 实时上下行速率折线图（60s 动态渲染）；
  - 活跃 TCP/UDP/DNS 连接详情列表（实时上传/下载流量、存活时间、路由策略）；
  - 运行日志实时展示与级别过滤（`ALL` / `INFO` / `WARN` / `ERROR` / `DEBUG`）；
  - 会话流量、今日流量与本月累计流量持久化统计。
- **一键配置备份与还原**：支持将节点配置、复合规则、Geo 订阅源、TUN 调优参数一键导出为标准 JSON 或即时还原。
- **Android 系统全版本兼容**：向下兼容 Android 9 (API 28)，全面适配 Android 14/15/16 前台服务类型规范 (`FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED` / `SPECIAL_USE`)。

---

## 📊 硬件实机验证与兼容性矩阵

本项目经过真实 Android 设备深度自动化与交互式回归测试：

| 测试设备 | 系统版本 | 架构 / 屏幕规格 | 实测验证状态 | 核心验证场景 |
| :--- | :--- | :--- | :---: | :--- |
| **Samsung Galaxy S24+ (SM-S9260)** | **Android 16** (API 36) | arm64-v8a (16KB Page), 1080x2340 | ✅ **全部通过** | 5G 蜂窝 IPv6 路由接管、YouTube 1080p 60fps 流畅播放、Google 全家桶 (Gmail/Maps/Translate/Gemini)、Anycast DNS 劫持、双上游竞速 |
| **Sony Xperia XZ1 Compact (SO-02K)** | **Android 9** (API 28) | aarch64, 720x1280 | ✅ **全部通过** | 低版本 VpnService 生命周期、切网自愈、低内存进程稳定性、国内白名单直连 |

---

## 🚀 快速开始

### 1. 编译构建

项目提供全自动构建脚本，通过隔离容器完成 Rust 交叉编译与 Android Gradle 打包：

```bash
# 执行一键构建 (自动完成 mirage-jni 编译、16KB 对齐与 APK 签名打包)
bash scripts/build-android.sh
```

构建产物存放于 `.build/out/`：
- `app-debug.apk`：最新构建版本软链接；
- `mirage-v<版本>-<时间戳>.apk`：版本化历史归档产物。

### 2. 安装与使用

```bash
# 通过 ADB 安装至设备
adb install -r .build/out/app-debug.apk
```

1. **添加节点**：打开 App → 进入「节点」Tab → 点击右上角添加（支持扫描/粘贴 `mirage://密码@host:端口?sni=...` 或手动配置）；
2. **启动连接**：在首页点击「连接」按钮，首次启动授予 Android 系统 VPN 权限；
3. **网络微调**：点击首页「TUN 性能与网络调优」卡片，可按需微调 MTU、IPv6 接管、QUIC 屏蔽与批处理队列；
4. **监控与排查**：进入「监控」Tab 查看实时速率曲线、DNS 解析日志与活跃连接状态。

---

## 📁 目录结构

```
Mirage-android/
├── native/
│   ├── mirage-core/          # 平台无关 Rust 原生内核
│   │   ├── src/crypto/       # TLS 1.3 仿真、ChaCha20-Poly1305 分帧、PFS
│   │   ├── src/direct/       # 多级分流引擎 (GeoSite / GeoIP / 规则匹配)
│   │   ├── src/direct_cn_ipv4.rs # 7730 条中国 IPv4 网段 (二分搜索)
│   │   ├── src/proxy/        # WarmPool 预热池 / 隧道管理 / UDP Mux
│   │   ├── src/tun/          # smoltcp TUN 栈、TCP/UDP 数据面、Anycast DNS
│   │   └── src/vendor/       # Mirage-rs 协议层 vendored 同步源码
│   └── mirage-jni/           # Android JNI 绑定与 16KB Page 对齐
├── android/                  # Android 客户端工程 (Kotlin)
│   └── app/src/main/
│       ├── aidl/             # ICoreService / ICoreCallback 跨进程接口
│       ├── java/.../core/    # CoreService / TunConfigStore / GeoManager / ConfigBackup
│       ├── java/.../data/    # NodeRepository / VpnRepository / TrafficStore
│       ├── java/.../ui/      # Home / Nodes / Rules / Traffic / Monitor 页面与 ViewModel
│       └── res/              # Material 3 布局、主题与多语言资源
├── scripts/
│   ├── build-android.sh      # 一键编译打包与自动化版本归档脚本
│   ├── test-e2e.sh           # 端到端沙箱自动化测试
│   └── vendor-sync.sh        # Mirage-rs 上游协议同步脚本
└── docs/                     # 架构设计、协议规范与移植指南
```

---

## 🔒 安全说明

继承自 [Mirage-rs](https://github.com/zdgt0226/Mirage-rs)：
- 本项目加密分帧与握手认证针对移动端弱网与防主动探测进行了深度优化；
- 请在受信任的服务端部署配套 Mirage-rs 服务节点；
- 开启前向保密 (PFS) 时需确保客户端与服务端均启用对应选项。

---

## 🤝 贡献与致谢 (Contributions & Acknowledgments)

本项目由人类开发者与前沿 AI 协同研发、审计与深度实机调优：

| Contributor | Role & Contributions |
| :--- | :--- |
| **[zdgt0226](https://github.com/zdgt0226)** | Project Creator & Maintainer |
| 🤖 **Google Gemini** | 移动端生命周期架构、IPv6/DNS 防泄漏、切网自愈、实机自动化验证与系统级连接调优 |
| 🤖 **Anthropic Claude** | 原生内核架构重构、FD 泄露治理、独立进程化与工程化迁移 |
| 🤖 **DeepSeek AI** | 安全审计、Linux/Rust 线程模型缺陷排查与协议栈深度分析 |

### 💡 架构参考与设计借鉴 (Design Inspirations & References)

在 Android 系统级网络调优与跨进程控制架构的演进过程中，特别致谢并借鉴了 **[meow-android](https://github.com/madeye/meow)** 项目的优秀工程实践：
- **`mimalloc` 原生内存优化**：引入高性能全局内存分配器，有效降低高并发短生命周期连接与 MMDB 扫描下的内存碎片，将原生内核驻留内存（RSS）降低至 ~7.4 MB；
- **底层物理网络绑定 (`setUnderlyingNetworks`)**：建立 VPN 时即时绑定底层活动物理网卡并监听网络能力变化，解决特定机型防火墙丢包并直通 Linux 内核 eBPF；
- **`strip_and_inject` 配置安全清洗**：在载入用户/订阅规则前执行自动化清洗，剔除无效格式与自环端口，强制注入局域网私有网段直连与 Fake-IP 系统级保护；
- **流式日志/流量消费与连接重置**：提供轻量流式接口规避 Android Binder 1MB 事务上限（`TransactionTooLargeException`），并支持细粒度定向斩断僵尸连接。

---

## 📄 开源许可证

本项目基于 [MIT License](LICENSE) 协议开源。
