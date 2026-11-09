---
slug: background
title: Project background
role: project background
updated: "2026-11-10T01:08:26"
---

# Project background

## 这个项目是什么

[Mirage-rs](https://github.com/zdgt0226/Mirage-rs) 协议的 **Android 客户端**：把该协议的移动端裁剪内核（`mirage-core`, Rust）装进一个 `VpnService` 外壳，用用户态 TUN 做全流量代理。

## 为什么存在（有源码与文档支撑）

1. **上游只有服务端与桌面侧。** 移动端需要一份独立裁剪核：去掉 netlink 监听、裁 tokio feature、`opt-level="s"` 压 `.so`、按 16KB 页对齐，这些都不是上游关心的。
2. **DNS 泄漏是移动端的主要失效面。** 选择 Fake-IP + 全端口 53 劫持，使设备**不向任何本地网络发明文 DNS 查询**，域名随隧道上行由服务端远程解析。这是架构级目标，不是功能点。
3. **核心刻意做成平台无关。** `mirage-core` 是 `rlib`，不碰任何平台 API；Android 由 `mirage-jni` 包装，iOS 预留 `mirage-ios`（证据：`native/mirage-core/Cargo.toml` 的包注释 + `docs/IOS_PORTING.md`）。

## 工程立场（已被反复固化）

- **真机优先。** 结论以 `adb` 实测为准，不以结构推断为准。审计四批、UI 各阶段、下沉 P1–P3 都有真机记录。
- **反馈需独立复核。** 用户给的反馈与测试数据不预设正确，先做独立技术评估再改，见 [[feedback-evaluation-rule]]。
- **只沉淀「代码读不出来的」。** 协议细节看 `docs/PROTOCOL.md`，架构看 `docs/ARCHITECTURE_V2.md`，交接看 `docs/AUDIT_HANDOFF.md`。

## 支持下限

最低支持 **Android 9 / API 28** 安装运行。`minSdk = 26` 是余量，CI 解包产物断言 `minSdkVersion ≤ 28`，防依赖升级把它静默抬高。

## ⚠️ 低置信 / 待用户确认

以下两项**无法从代码与文档推断**，当前为空，等确认后再填：

- **目标用户群体**：是自用、小范围分发，还是公开发布？这会决定要不要做订阅管理、崩溃上报、应用商店合规。
- **明确的非目标**：如 iOS 是否真要做、是否永不做服务端、是否永不接入第三方协议（Shadowsocks/VLESS 等）。
