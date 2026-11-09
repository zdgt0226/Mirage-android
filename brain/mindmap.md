---
slug: mindmap
title: Feature mindmap
role: feature mindmap
updated: "2026-11-10T01:08:06"
---

# Feature mindmap

```mermaid
mindmap
  root((Mirage-Android))
    协议内核
      伪装 TLS 1.3 ClientHello
      PFS / Elligator2
      cipher agility
      WarmPool 暖池 Zero-RTT
      UDP 中继 / 可选 mux
    DNS 防泄漏
      Fake-IP 映射
      全端口 53 劫持
      国内双上游竞速
      AAAA 返空强制 v4
    路由分流
      用户复合规则
      GeoSite / GeoIP 动态库
      内置 CN IPv4 二分查找
      域名 IP 双重置信
      Bypass LAN
    TUN 调优
      MTU / 批处理深度
      RELAY_IDLE 空闲回收
      IPv6 接管
      屏蔽境外 QUIC
    UI 外壳
      全手势三页外壳
      Compose 渐进迁移
      节点管理 / 测速
      分流规则编辑
    可观测性
      速率曲线 / 延迟
      活跃连接列表
      结构化日志
      流量持久化统计
    工程门禁
      端点防泄露扫描
      JNI 符号集 fail-closed
      so 体积 / minSdk 断言
      容器内构建
```

## 读图提示

- 左半（协议 / DNS / 路由 / TUN）全在 `mirage-core`，平台无关，是 iOS 复用的那部分。
- 右半（UI / 可观测性 / 门禁）是 Android 特有的壳与工程面。
- 「工程门禁」不是装饰：端点扫描与 JNI 符号门禁都是 **fail-closed 硬门禁**，见 [[endpoint-leak-gate]] 与 [[jni-symbol-gate]]。
