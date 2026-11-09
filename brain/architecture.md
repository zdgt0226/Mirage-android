---
slug: architecture
title: System architecture
role: system architecture
updated: "2026-11-10T01:07:08"
---

# System architecture

三层架构，边界由**进程**与**语言**双重划开：UI 在主进程写 Kotlin，网络在 `:core` 进程跑 Rust，中间只有 AIDL。

```mermaid
graph TD
  subgraph APP["App 进程 (Kotlin)"]
    UI["MainActivity + Compose 手势外壳<br/>首页 / 节点 / 设置"]
    VM["ViewModel + Repository<br/>VpnRepository / NodeRepository"]
    CC["CoreController<br/>Binder 死亡代理 + 自动重连"]
  end
  subgraph IPC["AIDL Binder"]
    AIDL["IMirageCore / IMirageCallback<br/>控制 + 状态 + 遥测回流"]
  end
  subgraph CORE[":core 进程 (Kotlin 壳 + Rust 核)"]
    CS["CoreService : VpnService<br/>建 TUN fd / 前台通知 / 系统网络回调"]
    NL["NativeLoader<br/>只在 onStartCommand 加载"]
    JNI["mirage-jni (cdylib)<br/>JNI 边界 + tokio runtime + 看门狗"]
    MC["mirage-core (rlib, 平台无关)<br/>协议 / TUN 栈 / DNS / 路由 / 暖池"]
  end
  UI --> VM --> CC --> AIDL --> CS
  CS --> NL --> JNI --> MC
  CS -. "fd 移交" .-> JNI
```

## 职责边界

| 层 | 负责 | 明确不负责 |
|---|---|---|
| App 进程 | 渲染、用户意图、配置持久化 | 任何网络判定 |
| `:core` 壳 | `VpnService.Builder.establish()` 建 fd、前台服务、系统回调 | 健康判定、选路（已下沉，见 [[network-policy-offload]]） |
| `mirage-jni` | JNI 符号面、runtime 生命周期、崩溃隔离 | 协议细节 |
| `mirage-core` | 协议、smoltcp TUN 栈、DNS、路由、暖池 | 一切平台 API |

## 三条不可动摇的结构事实

1. **内核拿不到 TUN fd。** `VpnService.Builder.establish()` 是框架 API，必须由应用上下文调用。可达上限是「应用建 fd、移交内核」，这就是现状。绕开 VpnService 的方案在 Android 上不存在。
2. **`:core` 必须是独立进程。** 见 [[core-separate-process]]。UI 崩溃或退出不得影响隧道。
3. **`vendor/` 不参与编译。** 见 [[vendor-not-compiled]]——比对协议行为只能看 `native/mirage-core/`。

跨进程调用的查询路径一律要能在原生库未加载时返回安全默认值，见 [[native-loader-start-path-only]]。
