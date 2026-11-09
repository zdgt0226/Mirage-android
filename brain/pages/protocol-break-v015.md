---
id: protocol-break-v015
title: "编译协议锁定 mirage-rs v0.15, 不兼容 v0.14 及更早服务端"
category: decision
status: active
tags: [protocol, compat]
created: "2026-11-10T01:09:35"
updated: "2026-11-10T01:10:15"
---

## compiled_truth

**事实**：本仓编译进 APK 的协议与上游 mirage-rs **v0.15** 同版。v0.15 是**协议断代版**，不兼容 v0.14 及更早的服务端。

v0.15 引入的 fail-closed 行为：
- `hello_auth` 的 16B tag **绑定 `ClientHello.random`**；
- PFS 开启时 `ClientHello.random` 是客户端 X25519 临时公钥的 **Elligator2** 表示（`elligator2` crate，线上格式两端须同版）；
- **`TIME_SYNC` 缺失即断开**；
- **`CIPHER_ACK` 缺失即断开**（cipher agility：两端有 AES-NI 走 AES-256-GCM，否则 ChaCha20-Poly1305）。

**已实测验证**：本地自建 v0.17.12 服务端（经 `adb reverse` 接入）与本客户端互通，846 次握手成功。上游 v0.15.1 → v0.17.12 每个 release 都声明「无协议变更」「两端互通」，所以 **v0.17.x 服务端不需要客户端改协议**。

**推论**：「连不上」不要先归因协议版本。生产节点的实际症状是 TCP 4ms 连通后服务端零字节（`sh=false`），属节点侧问题。诊断口径见 [[connection-state-truthfulness]]。

版本号的显示坑见 [[vendor-not-compiled]]。


## timeline

- time: 2026-11-10T01:09:35
  kind: decision
  summary: "Created this page: 编译协议锁定 mirage-rs v0.15, 不兼容 v0.14 及更早服务端"
  source: native/mirage-core / docs/PROTOCOL.md
  affects: [protocol-break-v015]

- time: 2026-11-10T01:10:15
  kind: decision
  summary: "固化协议断代事实与两端同版约束"
  source: docs/PROTOCOL.md / native/mirage-core
  affects: [protocol-break-v015]
