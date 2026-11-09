---
slug: stack
title: Tech stack
role: tech-stack choices
updated: "2026-11-10T01:07:25"
---

# Tech stack

技术选型与**选它的理由**。版本以 `native/*/Cargo.toml`、`android/app/build.gradle.kts`、`android/gradle/libs.versions.toml` 为准。

## Android 侧

| 域 | 选择 | 理由 |
|---|---|---|
| 语言 | Kotlin | 唯一选项；AIDL 与 VpnService 都是 Java 框架 API |
| UI | Compose (BOM 2025.02.00 / foundation 1.7.8) + 遗留 View 岛 | 渐进迁移，不做一次性重写，见 [[gesture-shell-navigation]] |
| SDK | `compileSdk`/`targetSdk` = 36，`minSdk` = 26 | 支持下限是 **Android 9 / API 28**；26 是余量，CI 断言产物 `minSdkVersion ≤ 28` 防依赖静默抬升 |
| 跨进程 | AIDL + Binder 死亡代理 | 见 [[core-separate-process]] |
| 构建 | Gradle KTS + version catalog，**仅容器内** | 见 [[container-only-build]] |

## Rust 侧（`mirage-core` 0.3.0 / `mirage-jni` 0.3.0）

| 域 | 选择 | 理由 |
|---|---|---|
| 运行时 | `tokio` 1.52（裁剪 feature，无 `full`） | 移动端体积；drop 语义有坑，见 [[tokio-runtime-drop-panic]] |
| TUN 协议栈 | `smoltcp` 0.13（`medium-ip`，TCP+UDP+IPv6） | 用户态全流量代理的唯一成熟 no_std 栈 |
| 加密 | `ring` 0.17 + `chacha20-poly1305` 分帧 + `hkdf`/`hmac`/`sha2` | 与上游 Mirage-rs 对齐 |
| PFS | `x25519-dalek` 3.0 + `elligator2` 0.1 | 临时公钥需 Elligator2 编码，**线上格式两端须同版** |
| 热切换 | `arc-swap` | 运行时换节点不停隧道 |
| 分配器 | `mimalloc`（`:core` 进程内） | relay 路径分配密集 |
| 产物 | `cdylib` + `strip` + `opt-level="s"` + `lto` + `codegen-units=1` | `.so` 体积优先；CI 有体积守卫 |
| 兼容 | arm64-v8a，16KB 页对齐 | Android 15/16 硬要求 |

## 门禁

- `scripts/check-no-real-endpoints.sh`：硬门禁，见 [[endpoint-leak-gate]]
- JNI 符号集 fail-closed：见 [[jni-symbol-gate]]
- `cargo clippy --lib` + `cargo fmt`（**按 crate 分别跑**，CI 不 lint 测试代码）
- `.so` 体积守卫、APK `minSdkVersion` 断言
