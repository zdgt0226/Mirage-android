---
slug: flow
title: Key flows
role: key flows
updated: "2026-11-10T01:07:54"
---

# Key flows

一次「点开关 → 某 App 访问境外站点」的完整路径。两段分开看：**启动**与**数据面**。

## 启动

```mermaid
sequenceDiagram
  participant U as 用户
  participant A as App 进程
  participant S as :core (CoreService)
  participant J as mirage-jni
  participant C as mirage-core
  U->>A: 点首页开关
  A->>S: AIDL start(config)
  S->>S: VpnService.Builder 加地址/路由/DNS
  S->>S: establish() 得 TUN fd
  S->>J: NativeLoader.load() 后 start(fd, config)
  J->>J: 建 tokio runtime + 看门狗
  J->>C: TunStack::new(fd) + WarmPool 预热
  C-->>J: 隧道就绪 / 健康位
  J-->>S: 状态
  S-->>A: onStateChanged(Connected) + 遥测流
```

`NativeLoader.load()` **只在 `onStartCommand` 里跑**，所以仅 bind 不 start 时任何原生查询都走安全默认值（[[native-loader-start-path-only]]）。

## 数据面

```mermaid
sequenceDiagram
  participant App as 任意 App
  participant T as TUN (smoltcp)
  participant D as DNS 引擎
  participant R as 路由引擎
  participant P as WarmPool
  participant Srv as 服务端
  App->>T: DNS 查询 (任意 53 端口目标, 全端口劫持)
  T->>D: 转交
  alt 代理域名
    D-->>App: fake-IP (198.18.0.0/16 内, 0ms, 不出网)
  else 直连域名
    D->>D: 国内双上游竞速
    D-->>App: 真实 IP
  end
  App->>T: 连 fake-IP
  T->>R: 四级判定 (见 four-tier-routing)
  R->>P: 取一条预热隧道 (Zero-RTT)
  P->>Srv: 伪装 TLS ClientHello + hello_auth
  Srv-->>P: ServerHello / TIME_SYNC / CIPHER_ACK
  P->>Srv: [2B 长度][目标 host:port] + 加密分帧
  Note over P,Srv: 携**域名**出隧道, 服务端在干净网络远程解析
  Srv-->>App: 回程解帧后伪源回包
```

要点：

- **fake-IP 反查域名后随隧道上行**，服务端远程解析——这是抗 DNS 污染的根，不是单纯「加密 DNS」。
- **Zero-RTT 来自暖池**，不是协议特性：池里已握完手，新连接直接发目标头。
- 网络切换（Wi-Fi↔蜂窝）靠 stale 探测 + 首写重试兜底；移动端裁掉了 netlink 监听。
- 路由/DNS/健康判定全在内核，壳只建 fd 与执行重启（[[network-policy-offload]]）。
