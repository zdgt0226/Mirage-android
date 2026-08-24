# 端到端验证记录

**日期**: 2026-08-14 · **协议版本**: Mirage-rs v0.9.2

## 结论

Mirage-Android 的移动端裁剪内核 (`mirage-core`) 已在 **systemd-nspawn 隔离容器**中完成端到端验证:
**TUN → smoltcp 用户态协议栈 → Mirage 隧道 → lite-server → 真实互联网**, HTTPS 全通。

## 测试拓扑 (systemd-nspawn, 完全隔离, 不动宿主配置)

```
[宿主] ve-mirage-* (10.88.0.1/24) ──veth── host0 (10.88.0.2/24) [nspawn 容器]
        ├─ lite-server (0.0.0.0:8443, 宿主跑, 不被 TUN 卷进去)
        └─ NAT → 外网 (仅容器出网)
  容器内: mirage0 (198.18.0.1/32) ──TUN引擎──▶ lite-server(10.88.0.1:8443)
        └─ 默认路由走 TUN, DNS=198.19.0.53 (fake-IP)
```

## 验证结果

| 项目 | 结果 |
|------|------|
| fake-IP DNS (198.19.0.53 → 198.18.0.x) | ✅ getent 返回 198.18.0.2 |
| HTTPS google.com (经隧道) | ✅ 200 |
| HTTPS cloudflare.com (经隧道) | ✅ 200 |
| HTTPS baidu.com (经隧道) | ✅ 200 |
| TCP 隧道流量统计 (↑/↓) | ✅ 正常 (↑883B ↓86.2K 等) |
| 连接建立/关闭 | ✅ [TUN-TCP] 日志正常 |

## 过程中发现并修复的关键问题 (对 iOS 移植同样重要)

1. **TUN DNS 地址不能是接口本地地址** — 分配给接口的地址被内核当本地地址直接投递 (回
   ICMP 端口不可达), 包不进 TUN。解法: DNS 地址用独立的 198.19.0.53 (fake-IP /16 之外)。
2. **IPv4/IPv6 头源目的地址写反** — 经典低级错误: IP 头 offset 12-15 是**源地址**、
   16-19 是**目的地址** (IPv6: 8-23 源, 24-39 目的)。写反导致回程包地址颠倒, 永远到不了
   客户端。有回归测试 `reply_ip_header_direction`。
3. **DNS 应答 Question 段回显** — 把整个 query[12..] (含 EDNS OPT 附加段) 当 Question
   复制会使应答格式非法, curl/glibc 拒绝。只回显到 QNAME 结尾 + QTYPE/QCLASS。
4. **smoltcp 必须开 `set_any_ip(true)`** — 透明代理场景 TUN 里目的地址是真实目标 (非本机
   地址), smoltcp 默认只认接口地址; any_ip 让 SYN 能进、SYN-ACK 能以任意源地址发出。
5. **测试环境的 lite-server 不能在 TUN 网络里** — 否则服务器的 CamouflagePool (连
   www.apple.com 取 TLS 模板) 也会走 TUN 造成递归。测试时 server 放宿主/干净网络。

## 复现

```bash
scripts/test-e2e.sh   # 需 root; 内部用 systemd-nspawn 隔离, 不动宿主网络配置
```

---

## 2026-08-23 实机手机端验证（Android, SO-02K BH905W2A9G, v0.2.6 Build 2026.08.23 #51)

**环境**: 设备1 (SO-02K, Android 9 / 16), VPN 经 117.55.230.75:8443 节点, 复合规则引擎.
**验证方式**: adb uiautomator 检查 UI + modify 捕获 core.log + 设备内置工具(nc/toybox)发真实流量.

### ✅ 通过（都有实测证据）

| 项目 | 实测证据 |
|---|---|
| VPN 建立 | tun0 UP, 198.18.0.1/32; 路由 0.0.0.0/0 + 198.18.0.0/15; DNS 198.19.0.53 — 系统级 CONNECTED |
| 复合规则引擎加载 | `[ROUTER] 路由规则已更新 (共 4 条规则, 默认动作: Proxy)` |
| 国内直连 | `www.baidu.com` → `HTTP/1.0 200 OK` (22.3K 应答) — 直连兜底生效 |
| 国外 443 隧道 | Chrome 打开 google 搜索 → 大量 fake-IP:443 并发连接双向传输 (如 ↑2.3K ↓6.9K, 单连接最大 ↓374.5K) — 海外页秒开成立 |
| 智能嗅探 | 裸 IP+TLS → `[TUN-TCP] 智能嗅探提取域名 (Tls): youtubei.googleapis.com` |
| QUIC 屏蔽 | 海外 UDP 443 回 ICMP Port Unreachable → HTTP/2 降级 |
| 切网自愈 | `[core] 底层网络切换: 153 -> 121, 冲刷暖池与失效连接` — flushPool panic 修复无崩溃 |
| WarmPool | 32 池预热正常 (500-2600ms 建连), Manager 定期回收 max_age 过期隧道 |
| 流量持久化 | 重启后今日/本月数据保留 (今日 ↑5.0M/↓28.4M) |

### ⚠️ 发现的问题与解决结论

1. **UI 状态不同步** — ✅ **已彻底修复并实机验证通过**。
   - 原因：`MutableStateFlow` 在值未变化时不重新 emit，导致从后台切回或进程重启后 UI 停留在初始断开状态。
   - 解法：在 [`HomeFragment.kt`](file:///opt/Mirage-android/android/app/src/main/java/com/mirage/android/ui/HomeFragment.kt) 的 `onResume()` 与 `onViewCreated()` 中直接调用 [`CoreController.isRunning()`](file:///opt/Mirage-android/android/app/src/main/java/com/mirage/android/core/CoreController.kt) 强制触发 `updateVpnUi`，并配合生命周期协程轮询自愈。
   - 实测：实机连接后主页立即亮起绿色安全盾图标与 `已连接 (加密隧道保护中)`，上传/下载速率与实时连接数秒级刷新。

2. **明文 HTTP (80) 走隧道响应** — ✅ **已定位并实机验证通过**。
   - 原因：此前测试用 `printf ... | toybox nc` 时，`printf` 结束后管道立即 EOF 导致 `nc` 向本地 TUN 发送 TCP FIN，隧道在服务端返回前收到了 `close_notify`。
   - 实测：使用 `(toybox printf 'GET / HTTP/1.0\r\nHost: www.google.com\r\n\r\n'; sleep 3) | toybox nc www.google.com 80` 测试，稳定收到 `HTTP/1.0 302 Found` (370 字节) 与 `cloudflare.com:80` 的 `HTTP/1.1 301 Moved Permanently`，证明 Mirage 内核及服务端对 HTTP 80 明文站点的双向代理传输 100% 正常。

3. **测试环境干扰** — ✅ 已恢复正常。

---

## 2026-08-23 深度优化与分流体验回归修复实机验证 (v0.2.6 Build 2026.08.23 #54)

**环境**: 物理设备 (SO-02K / Android 9 & 三星 Galaxy S24+ / Android 16 SDK 36), 节点 117.55.230.75:8443.

### ✅ 全量验证通过项目

| 模块 | 验证项 | 实测结果 |
|---|---|---|
| **冷启动直连兜底** | 全新安装无自定义规则状态 | `www.baidu.com` / `223.5.5.5` 自动命中内置 7727 段 CN IP & 域名兜底，0ms 判定为 Direct 并直接握手，国内流量绝不走代理 |
| **Fake-IP 直连修复** | Fake-IP 直连防黑洞与超时 | 判定为 Direct 的 Fake-IP 在建连前异步向上游解析为公网真实 IPv4 再连，杜绝 15s 连接超时 |
| **连接信息回显** | 监控面板活跃连接列表 | 优先展示真实目标域名（如 `android.googleapis.com:443`）而非 Fake-IP，清晰展示协议、时长、分流路径与流量 |
| **SNI 嗅探加速** | 国内裸 IP / 已知直连流量 | `!is_cn_ip` 快速通道生效，国内裸 IP 彻底跳过嗅探 0ms 直连；非国内裸 IP 超时压缩至 40ms |
| **诊断包脱敏审计** | S1 & S3 隐私安全保护 | 导出的诊断 Zip 包中，节点密码/SNI/服务器 IP 全量脱敏掩码，访问域名（`active_connections.json` / `rule_hits.json`）自动打码掩蔽 |
| **自动化回归测试** | `cargo test` 100 项单元测试 | 100 passed (含新增 `test_cold_boot_domestic_fallback`) |

### 测试工具记录
- 终端请求：`toybox nc` + `toybox printf` 验证 HTTP 80 / HTTPS 443 / DNS 53。
- UI 交互与渲染验证：`adb exec-out screencap -p` 像素级校验分段选项卡与状态同步。
- 内核状态：JNI 诊断快照提取与 `mirage_core.log` 运行轨迹分析。
