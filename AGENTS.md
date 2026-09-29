# Mirage-Android Agent Collaboration Guidelines

## 1. 独立技术审查与反馈评估规则 (Critical Feedback Evaluation Rule)
* **核心原则**：用户的反馈、假设或测试数据并非 100% 绝对正确。
* **执行准则**：
  1. 在接收到用户的任何反馈、优化建议或测试数据时，**绝不盲目套用或草率修改**。
  2. 必须首先进行**独立技术评估与边界推演**（包括但不限于：RFC 网络协议规范、CIDR 范围与路由拓扑、DNS 投毒与双校验边界、Smoltcp/Tokio 并发安全、跨平台/跨协议兼容性等）。
  3. 确认建议的技术合理性、数据可靠性与边界安全性后，再给出专业判定并执行相应的优化修改；若发现潜在缺陷或误伤，应主动指出并提出更健壮的方案。

## 2. 交互规范 (Communication Protocol)
* **前缀规范**：所有面向用户的回复与操作步骤必须以 `【操作说明】` 开头。
* **真机适配**：默认优先使用 `adb devices` 检测到的活动设备（如 `BH905W2A9G` / `SM_S9260`）进行全量构建与实机验证。

## 3. 当前工作交接 (Active Handoff)
* **动工前必读**：[`docs/AUDIT_HANDOFF.md`](docs/AUDIT_HANDOFF.md)。其中记录了 Android 客户端审计的已完成项（第 0–4 批全量闭环，含多模型复审返工、真机回归与 CI 绿灯）以及已验证的结论采信规则。
* **UI 路线图**：[`docs/UI_REDESIGN_PROPOSAL.md`](docs/UI_REDESIGN_PROPOSAL.md)。流体交互、自适应布局与 Compose 演进的技术方案与实测基线。
* **构建环境**：宿主机无 gradle / 无 wrapper。Kotlin 与 APK 构建**必须**在 systemd-nspawn 容器 `/var/lib/machines/android-builder` 内进行，具体命令见交接文档第 0 节。Kotlin 改动未经容器编译不得提交。
* **对照实现**：`/opt/reference/meow-android` 为本地可读源码，比对时以源码为准，禁止凭记忆断言。`sing-box` 无本地副本，不得虚构其文件路径。

## 4. 安全与防泄露规范 (Security & Endpoint Guardrails)
* **严禁硬编码敏感信息**：禁止在仓库中提交任何真实服务器地址、节点链接、口令、密钥（包括测试、示例、文档、环境变量默认值等）。
* **测试与文档占位规范**：测试与示例代码一律使用 RFC 文档专用保留地址（如 `203.0.113.x`、`198.51.100.x`、`192.0.2.x`、`2001:db8::/32`）或保留域名（如 `example.com`、`.example`、`.test`、`localhost`）。
* **真实参数传递规范**：需要连接真实服务器的测试或示例工具，真实参数只能经环境变量传入且**严禁设置任何公网默认值**（缺失时明确提示用法并以非零状态码退出）。
* **CI 门禁与白名单维护**：CI 中的 `bash scripts/check-no-real-endpoints.sh` 为硬门禁。若因业务/测试需要新增合法的公网地址（如公共 DNS、云元数据 IP），必须同步更新 `scripts/endpoint-allowlist.txt` 并逐行注明用途与技术理由。
