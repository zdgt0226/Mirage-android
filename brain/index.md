# Brain Index

_Auto-generated. Last updated 2026-11-09T17:13:51.640Z._

- [connection-state-truthfulness](pages/connection-state-truthfulness.md) — category: decision | tags: [diagnostics] | **问题**：软件自测总报「连接成功」，而生产节点实际不可用。
- [container-only-build](pages/container-only-build.md) — category: decision | tags: [build, env] | **宿主机没有 gradle、没有 wrapper、`ANDROID_HOME` 为空。
- [core-separate-process](pages/core-separate-process.md) — category: decision | tags: [arch, ipc] | **决定**：VPN 内核跑在独立进程 `:core`（`CoreService : VpnService` + JNI），UI 主进程只通过 AIDL（`IMirageCore` / `IMirageCallback`）控制它，并带 Binder 死亡代理与自动重连。
- [device-first-verification](pages/device-first-verification.md) — category: decision | tags: [process, testing] | **采信规则一：结论以真机实测为准。
- [endpoint-leak-gate](pages/endpoint-leak-gate.md) — category: decision | tags: [security, ci] | **硬规范（AGENTS.md §4，不可弱化）**：
- [fake-ip-dns-anti-leak](pages/fake-ip-dns-anti-leak.md) — category: decision | tags: [dns, security] | **决定**：DNS 不走「加密上游」路线，走 **Fake-IP**：代理域名在本地 0ms 返回虚拟地址，App 连该地址时引擎反查域名，**域名随隧道上行由服务端在干净网络远程解析**。
- [feedback-evaluation-rule](pages/feedback-evaluation-rule.md) — category: decision | tags: [process] | **规则（AGENTS.md §1，用户亲自固化）**：用户的反馈、假设或测试数据**并非 100% 正确**。
- [four-tier-routing](pages/four-tier-routing.md) — category: concept | tags: [routing] | 判定按**固定四级**短路，顺序本身是语义：
- [gesture-shell-navigation](pages/gesture-shell-navigation.md) — category: decision | tags: [ui] | **决定**：参照 Nokia N9 的全手势逻辑重做导航——三页 `HorizontalPager` 外壳，**首页只留连接开关与节点下拉**；首页上滑调出详细面板（速率 / 延迟 / 连接列表 / 流量统计），右滑进节点管理，左滑进详细设置。
- [jni-symbol-gate](pages/jni-symbol-gate.md) — category: decision | tags: [ci, jni] | **决定**：`CoreManager.importCore` 与 `NativeLoader.load` 都调 `NativeSymbolPolicy.verify()`，比对 `.so` 的 `.dynsym` 导出与 `MirageNative` 反射出的 native 方
- [native-loader-start-path-only](pages/native-loader-start-path-only.md) — category: decision | tags: [jni, ipc] | **原则（守卫第 3 条）**：**绝不在查询/设置等非启动路径隐式触发 `NativeLoader.load()`。
- [network-policy-offload](pages/network-policy-offload.md) — category: decision | tags: [arch, offload] | **决定（2026-11-03 方案，P1–P3 已落地）**：网络策略判定全部下沉到 `mirage-core`，Kotlin 外壳退回「建 fd + 执行」的角色。
- [protocol-break-v015](pages/protocol-break-v015.md) — category: decision | tags: [protocol, compat] | **事实**：本仓编译进 APK 的协议与上游 mirage-rs **v0.15** 同版。
- [stacked-pr-merge-protocol](pages/stacked-pr-merge-protocol.md) — category: decision | tags: [process, ci] | **规程一：栈式 PR 必须用合并提交，不能 squash。
- [tokio-runtime-drop-panic](pages/tokio-runtime-drop-panic.md) — category: concept | tags: [rust, concurrency, gotcha] | **机制**：在某个 tokio runtime **自己的工作线程**上 drop 掉该 runtime 的最后一个 `Arc<Runtime>`，会 panic（`shutdown.rs:51:21`）。
- [vendor-not-compiled](pages/vendor-not-compiled.md) — category: concept | tags: [protocol, gotcha] | `native/mirage-core/src/vendor/` 是**上游源码的参考副本**，用于比对，**不参与编译**。
