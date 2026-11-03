# Vendored 同步记录

- 上游仓库: /opt/Mirage-rs
- 上游 commit: `62cf686e9881476ad4b87be5e1e5373cb29891e4`
- 同步时间: 2026-11-03T10:26:39+08:00

## ⚠️ vendor/ 不参与编译

lib.rs 对本目录的唯一引用是 `include_str!("vendor/SYNC.md")`（版本字符串），
全树没有 `include!` 也没有 `#[path]`。实际编译的是手工裁剪树
`src/{crypto,proxy,dns,config.rs,monitor.rs,time_sync.rs,node_uri.rs,net_util.rs}`。

**所以上面那个 commit 号只代表"参考副本的版本"，不代表本 APK 的协议行为。**
要真正升级协议，必须把 vendor/ 与 src/ 的差异手工移植进裁剪树，
移植后请在本文件追加一行说明移植到哪个 commit。

## 同步后必须做的事
1. 检查 $DEST 里是否有对**未 vendored 模块**的引用 (`crate::api` / `crate::ebpf` /
   `crate::config_watcher` / `crate::net_monitor` / `crate::startup` /
   `crate::proxy::wg` / `crate::proxy::shadowsocks` / `crate::proxy::splice` 等) ——
   这些是移动端裁剪掉的模块, 出现引用说明裁剪边界需要调整。
   (注: `crate::monitor` 不在此列 —— monitor.rs 本就在同步清单内。)
2. 检查移动端补丁是否仍适用 (搜索 `MIRAGE_MOBILE` 标记)。
3. `cd native && cargo check` 过一遍。

## 移动端补丁清单 (在 vendored 副本中, 上游没有)
- `proxy/brutal.rs`: `#[cfg(target_os = "android")] const SO_COOKIE = 67` (bionic 内核支持,
  libc crate 未对 android target 暴露该常量)
