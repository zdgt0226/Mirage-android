---
id: fake-ip-dns-anti-leak
title: "Fake-IP + 全端口 53 劫持: 设备不发明文 DNS"
category: decision
status: active
tags: [dns, security]
created: "2026-11-10T01:09:35"
updated: "2026-11-10T01:10:41"
---

## compiled_truth

**决定**：DNS 不走「加密上游」路线，走 **Fake-IP**：代理域名在本地 0ms 返回虚拟地址，App 连该地址时引擎反查域名，**域名随隧道上行由服务端在干净网络远程解析**。设备因此**不向任何本地运营商/物理网络发明文 DNS 查询**。

**三个容易写错的具体数字**：

| 用途 | 值 | 说明 |
|---|---|---|
| 路由接管 / 判定网段 | `198.18.0.0/15` | 注入 Proxy 路由；`is_cn_ip` / `is_private_ip` 必须在入口短路为 false，防代理流量倒灌 |
| fake-IP **分配**网段 | `198.18.0.0/16` | `FAKE_IP_CIDR`，比上游的 /15 窄，刻意把 `198.19.0.0/16` 留给 TUN DNS 地址，避免分配碰撞；从 `198.18.0.2` 顺序分配，6.5 万域名足够手机 |
| TUN DNS 虚拟地址 | `198.19.0.53` | 发往它的 TCP 也拦（挡 Android 14/15/16 的 DoT 853 探测，0ms 快速 RST） |

**劫持范围**：拦**所有进入 TUN 的 53 端口 UDP/TCP 查询**，含硬编码 `8.8.8.8` / `1.1.1.1` / `114.114.114.114`，伪源原路应答，杜绝顽固 App 旁路。

**两条协议级约束**：
- `AAAA` 返回空 answer（NOERROR）强制回落 IPv4——fake-IP 只做 v4；
- DNS 应答 Question 段只回显到 QNAME 结尾，**EDNS 附加段必须剥离**，否则格式非法。

直连域名走国内双上游并发竞速。路由侧配合见 [[four-tier-routing]]。


## timeline

- time: 2026-11-10T01:09:35
  kind: decision
  summary: "Created this page: Fake-IP + 全端口 53 劫持: 设备不发明文 DNS"
  source: native/mirage-core/src/engine.rs / direct.rs
  affects: [fake-ip-dns-anti-leak]

- time: 2026-11-10T01:10:41
  kind: decision
  summary: "固化 Fake-IP 网段划分与劫持范围, 含 /15 与 /16 的区别"
  source: "native/mirage-core/src/engine.rs:18 / direct.rs"
  affects: [fake-ip-dns-anti-leak]
