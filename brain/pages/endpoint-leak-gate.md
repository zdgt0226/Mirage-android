---
id: endpoint-leak-gate
title: "端点防泄露扫描是 CI 硬门禁, 白名单需逐行注明理由"
category: decision
status: active
tags: [security, ci]
created: "2026-11-10T01:09:47"
updated: "2026-11-10T01:13:28"
---

## compiled_truth

**硬规范（AGENTS.md §4，不可弱化）**：

1. **严禁硬编码敏感信息**——真实服务器地址、节点链接、口令、密钥一律不得入库，**测试、示例、文档、环境变量默认值全都算**。
2. **占位必须用保留地址**：`203.0.113.x` / `198.51.100.x` / `192.0.2.x` / `2001:db8::/32`，或 `example.com` / `.example` / `.test` / `localhost`。
3. **真实参数只经环境变量传入，且严禁设任何公网默认值**；缺失时提示用法并以非零状态码退出。
4. **`bash scripts/check-no-real-endpoints.sh` 是 CI 硬门禁。** 新增合法公网地址（公共 DNS、云元数据 IP 等）必须同步更新 `scripts/endpoint-allowlist.txt`，**逐行注明用途与技术理由**。

扫描分三段：A 查节点链接格式与端点，B 查环境变量回退默认值与硬编码长口令/公网地址，C 查源码与文档里未授权的公网 **IPv4 与 IPv6** 字面量（IPv6 是 2026-11-09 补上的）。

**正确的修法示例（三次都是自己的代码或文档被拦）**：
- 日志里用 `format!` 拼「节点链接 scheme 前缀 + 脱敏后的 host:port」被 A 段判为未授权主机——把 scheme 抽成 `const SCHEME` 规避，而不是放宽规则；
- 新增 CIDR 边界字面量触发 C 段——**按 §4 更新白名单并写理由**，不是改门禁；
- **本页初版自己被 A 段拦下**：正文里原样写出了那个带 scheme 的 `format!` 字面量。文档引用这类格式时写成文字描述，不写可被扫中的字面量。

**配套**：节点 URI 进日志必须脱敏，且脱敏逻辑**不得复用** `Node.server` / `Node.port` 这类宽松 getter——`substringBeforeLast(":")` 在没有分隔符时返回整串，畸形 URI 会把口令当 host 打出来。本地测试服务端配置与节点链接含真口令，只存放在会话 scratchpad（`chmod 600`），永不入库、永不贴进提交或文档。


## timeline

- time: 2026-11-10T01:09:47
  kind: decision
  summary: "Created this page: 端点防泄露扫描是 CI 硬门禁, 白名单需逐行注明理由"
  source: AGENTS.md / scripts/check-no-real-endpoints.sh
  affects: [endpoint-leak-gate]

- time: 2026-11-10T01:11:55
  kind: decision
  summary: "固化防泄露四条规范与门禁维护方式"
  source: "AGENTS.md §4 / scripts/check-no-real-endpoints.sh"
  affects: [endpoint-leak-gate]

- time: 2026-11-10T01:13:28
  kind: decision
  summary: "重写修法示例一: 原文里的链接字面量被 Check A 当场拦下, 改成文字描述"
  source: "AGENTS.md §4 / scripts/check-no-real-endpoints.sh"
  affects: [endpoint-leak-gate]
