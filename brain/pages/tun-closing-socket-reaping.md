---
id: tun-closing-socket-reaping
title: "收尾状态 socket 按「最近进展」计时回收; 用户断开走 RST, 正常关闭走 FIN"
category: decision
status: active
tags: [tun, smoltcp, tcp]
created: "2026-11-11T09:56:28"
updated: "2026-11-11T09:56:28"
---

## compiled_truth

TUN 栈 (`native/mirage-core/src/tun/mod.rs`) 的 sweeper 回收 smoltcp socket 时, **收尾状态 (FinWait1 / FinWait2 / Closing) 的孤儿时限必须从「最近一次进展」起算, 不能从 socket 创建时刻起算**。裁决逻辑在纯函数 `should_reap`, 有单测守着。

**曾经的缺陷**：原实现用 `created_at` (创建时刻) 去比 15 秒。任何存活超过 15 秒的连接一旦进入收尾状态, 下一轮 sweep 就被摘掉 —— 发送缓冲里未发出的数据 (至多 `SOCK_BUF` = 256KB) 连同 FIN 一起丢, 客户端随后的 ACK 撞上不存在的 socket 而收到 RST。

**触发条件 (三者同时成立)**：服务端先关连接; 客户端读得比对端发得慢 (限速下载、暂停的播放器、后台应用); 连接已存活超过 15 秒。实机对照:

| 场景 | 修复前 | 修复后 |
| --- | --- | --- |
| 慢读 + 服务端先关 + 连接 > 15s (10MB) | 少收 164,141 字节, 连接重置 | 完整 |
| 快读 + 服务端先关 | 完整 | 完整 |
| 慢读 + 服务端先关 + 连接 < 15s (3MB) | 完整 | — |

客户端先关 (HTTP keep-alive / H2 的常态) 不触发, 所以这个缺陷平时不显形。

**「进展」的定义**：发送队列长度 (`send_queue()`) 发生变化, 即对端仍在收数据并回 ACK。对端连续 15 秒完全不读 (窗口一直为零) 仍会被回收 —— 这是有意保留的泄漏上界, 不是遗漏。

**两种关闭语义, 不要混用**：

- **正常关闭** (转发结束、流 drop): `TunTcpStream::close()` → smoltcp `close()`, 排空缓冲后发 FIN。不向客户端回 RST。
- **用户主动断开** (连接列表里的「断开」): `TunTcpAbortHandle::abort()` → smoltcp `abort()`, 立即回 RST 并丢弃未发数据 (RFC 9293 ABORT)。句柄要在 `tokio::io::split` 拆流之前取, 且与流共享 `alive`, 避免 RST 之后流 drop 再补一个 FIN。实测全速下载中点「断开」, 客户端 0.4 秒内收到连接重置。

**排查这类问题时的一个陷阱**：用 `curl --limit-rate` 测「断开后多久生效」会得到几十秒的假象。限速读取方在手机系统的接收缓冲 (本机 `tcp_rmem` 上限 2MB) 里压着大量已到达的数据, Linux 在队列读空之前不会向应用报告 FIN / RST。那段延迟在客户端内核里, VPN 内核管不到; 要测断开时延必须用不限速的读取方。

相关: [[core-owned-state-via-aidl]] (连接列表与断开入口)、[[device-first-verification]]。


## timeline

- time: 2026-11-11T09:56:28
  kind: decision
  summary: "Created this page: 收尾状态 socket 按「最近进展」计时回收; 用户断开走 RST, 正常关闭走 FIN"
  source: 2026-11-11 fix/tun-closing-orphan-ttl
  affects: [tun-closing-socket-reaping]

- time: 2026-11-11T09:56:28
  kind: decision
  summary: "修复慢读下载尾部截断时确立"
  source: "设备 BH905W2A9G 对照实验"
  affects: [tun-closing-socket-reaping]
