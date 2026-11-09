---
id: feedback-evaluation-rule
title: "用户反馈与测试数据须先独立技术复核, 再改代码"
category: decision
status: active
tags: [process]
created: "2026-11-10T01:09:47"
updated: "2026-11-10T01:13:01"
---

## compiled_truth

**规则（AGENTS.md §1，用户亲自固化）**：用户的反馈、假设或测试数据**并非 100% 正确**。收到任何反馈、优化建议或测试数据时**绝不盲目套用或草率修改**，必须先做**独立技术评估与边界推演**——RFC 协议规范、CIDR 范围与路由拓扑、DNS 投毒与双校验边界、smoltcp/tokio 并发安全、跨平台跨协议兼容性——确认技术合理性、数据可靠性与边界安全后再动手；发现潜在缺陷或误伤要主动指出并给更健壮的方案。

**这条规则救过场的真实案例**：用户提出「Mirage-rs 自 v0.15 起协议断代，所以连不上是版本不兼容」。独立验证的结果是**该假设不成立**——自建 v0.17.12 服务端与客户端 846 次握手成功，上游 v0.15.1 → v0.17.12 每个 release 都声明无协议变更。真实成因是节点侧零响应（`sh=false`）。若照假设动手，会去改一个本来正确的协议实现。

**反向同样适用**：我自己的结论也要被证伪才算过关——见 [[tokio-runtime-drop-panic]] 里被真机否证的「机制已移除」断言。

**附带的严厉程度校准**：我曾两次夸大 `tls_padding` 缺失的严重性，纠正后的事实是——收端**恒剥零**不受该开关控制，上游默认也是 `false`，四个未调用 setter 里两个是服务端侧。属缺一个 opt-in，不是互通或指纹问题。

配套见 [[device-first-verification]]。


## timeline

- time: 2026-11-10T01:09:47
  kind: decision
  summary: "Created this page: 用户反馈与测试数据须先独立技术复核, 再改代码"
  source: "AGENTS.md §1 / git log f0f8f2a"
  affects: [feedback-evaluation-rule]

- time: 2026-11-10T01:13:01
  kind: decision
  summary: "固化 AGENTS §1 的独立复核规则与其真实触发案例"
  source: "AGENTS.md §1 / git log f0f8f2a"
  affects: [feedback-evaluation-rule]
