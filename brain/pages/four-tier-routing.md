---
id: four-tier-routing
title: "四级路由判定与域名/IP 双重置信校验"
category: concept
status: active
tags: [routing]
created: "2026-11-10T01:09:35"
updated: "2026-11-10T01:10:41"
---

## compiled_truth

判定按**固定四级**短路，顺序本身是语义：

1. 用户自定义复合规则（`AND`/`OR`，支持 Exact / Suffix / Keyword / Regex / IP-CIDR / Port / Protocol）；
2. 动态 GeoSite(CN) / GeoIP(CN) 数据库；
3. 内置 CN IPv4 网段（约 7730 条，**`O(log N)` 二分查找**，10ns 级）+ CN 常用域名白名单；
4. 默认策略（直连 / 代理 / 拦截）。

**为什么要「域名 + IP 双重置信校验」（方案 D）**：单看域名会被 `geosite:cn` 的误收条目坑——历史上 `geosite:cn` 误含境外域名，导致 Google 服务被判直连，再吃 DNS 污染 IP，表现为 Play 更新极慢。单看 IP 则会被污染结果骗。两者互为否证，才能把误判压下去。

**配套的边界修正（都是踩出来的）**：
- Fake-IP 网段必须先于 `GeoIP:private` 判定，否则 `198.18.0.0/15` 被当私有网段劫进直连；
- 局域网域名不得上隧道，子域不得被 LAN 规则劫持；
- 链路本地 `169.254.0.0/16` 不得卷进隧道（`168.0.0.0/6` 被拆成十条以挖掉它）；
- IPv6 判定不用 `2400::/12` 这种粗粒度段，改精确定位国内运营商与教育网专属段；
- 命中代理的 UDP 443 回 ICMP Port Unreachable，逼 YouTube / X / Chrome 快速回落 HTTP/2。

与 DNS 侧的分工见 [[fake-ip-dns-anti-leak]]。


## timeline

- time: 2026-11-10T01:09:35
  kind: decision
  summary: "Created this page: 四级路由判定与域名/IP 双重置信校验"
  source: native/mirage-core/src/direct.rs / git log
  affects: [four-tier-routing]

- time: 2026-11-10T01:10:41
  kind: decision
  summary: "固化四级判定顺序与双重置信校验的动机"
  source: native/mirage-core/src/direct.rs / git log
  affects: [four-tier-routing]
