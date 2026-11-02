//! 故障切换的状态与事件通道（下沉方案 P1：只建接口面，不含看门狗）。
//!
//! 职责边界见 `docs/CORE_OFFLOAD_PLAN.md`：
//! - 内核持有节点清单、切换策略与物理网络状态，P2 据此自主判定与热切换；
//! - 内核**不能**创建 TUN fd（`VpnService.Builder.establish` 是 Android 框架 API），
//!   因此需要重建隧道的重启只能发事件请求外壳执行。
//!
//! 本模块在 P1 阶段只提供存储与事件通道，`watchdog` 由 P2 填充。

use serde::Deserialize;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};

/// 事件类型：内核已自行热切换到新节点。
pub const EVENT_SWITCHED: i32 = 0;
/// 事件类型：内核判定需要重启隧道，请外壳执行（内核无法自建 TUN fd）。
pub const EVENT_RESTART_REQUESTED: i32 = 1;

/// 事件回调：`(kind, node_uri)`。平台包装层在 `start()` 时注册。
pub type FailoverEventFn = Box<dyn Fn(i32, &str) + Send + Sync>;

static EVENT_SINK: OnceLock<FailoverEventFn> = OnceLock::new();

#[derive(Debug, Clone, Deserialize)]
pub struct NodeEntry {
    pub uri: String,
    #[serde(default)]
    pub name: String,
}

/// 切换策略。字段与外壳 `SettingsStore` 既有语义保持一致，不新造概念。
#[derive(Debug, Clone, Deserialize)]
pub struct FailoverPolicy {
    /// `"best"` = 全量测速取最优；其余值 = 按清单顺序轮转。
    #[serde(default = "default_mode")]
    pub mode: String,
    /// 健康检查间隔（秒）。外壳默认 15。
    #[serde(default = "default_interval")]
    pub interval_sec: u32,
    /// 连续失败时的附加退避（秒）。
    #[serde(default)]
    pub backoff_sec: u32,
    /// 请求外壳重启前的等待（毫秒）。外壳原实现为 3000。
    #[serde(default = "default_restart_delay")]
    pub restart_delay_ms: u32,
}

fn default_mode() -> String {
    "best".to_string()
}
fn default_interval() -> u32 {
    15
}
fn default_restart_delay() -> u32 {
    3000
}

impl Default for FailoverPolicy {
    fn default() -> Self {
        Self {
            mode: default_mode(),
            interval_sec: default_interval(),
            backoff_sec: 0,
            restart_delay_ms: default_restart_delay(),
        }
    }
}

static NODES: OnceLock<Mutex<Vec<NodeEntry>>> = OnceLock::new();
static POLICY: OnceLock<Mutex<FailoverPolicy>> = OnceLock::new();

/// 当前物理网络句柄（Android `Network.getNetworkHandle()`）。0 = 未知。
static NET_HANDLE: AtomicU64 = AtomicU64::new(0);
/// 当前物理网络是否具备 Internet 能力。
static NET_HAS_INTERNET: AtomicBool = AtomicBool::new(false);

fn nodes_cell() -> &'static Mutex<Vec<NodeEntry>> {
    NODES.get_or_init(|| Mutex::new(Vec::new()))
}

fn policy_cell() -> &'static Mutex<FailoverPolicy> {
    POLICY.get_or_init(|| Mutex::new(FailoverPolicy::default()))
}

/// 注册事件回调。重复注册只有第一次生效（与 `protect` 一致）。
pub fn set_event_sink(f: FailoverEventFn) {
    let _ = EVENT_SINK.set(f);
}

/// 发事件给外壳。未注册回调时静默跳过（桌面端无外壳）。
pub fn emit_event(kind: i32, node_uri: &str) {
    if let Some(sink) = EVENT_SINK.get() {
        sink(kind, node_uri);
    }
}

/// 写入节点全表。入参为外壳既有的 `nodes_json` 形状：`[{"uri":"...","name":"..."}]`。
/// 返回写入的条目数；解析失败返回 `None`，不清空既有清单。
pub fn set_node_list(json: &str) -> Option<usize> {
    let parsed: Vec<NodeEntry> = serde_json::from_str(json).ok()?;
    let n = parsed.len();
    *nodes_cell().lock().unwrap_or_else(|e| e.into_inner()) = parsed;
    Some(n)
}

/// 读节点全表快照。
pub fn node_list() -> Vec<NodeEntry> {
    nodes_cell()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
}

/// 写入切换策略。解析失败返回 `false`，保留既有策略。
pub fn set_policy(json: &str) -> bool {
    match serde_json::from_str::<FailoverPolicy>(json) {
        Ok(p) => {
            *policy_cell().lock().unwrap_or_else(|e| e.into_inner()) = p;
            true
        }
        Err(e) => {
            tracing::warn!("[failover] 策略解析失败, 保留既有值: {e}");
            false
        }
    }
}

/// 读当前策略快照。
pub fn policy() -> FailoverPolicy {
    policy_cell()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
}

/// 外壳转发 `ConnectivityManager.NetworkCallback` 的结果。
///
/// 注册必须留在外壳（框架 API），内核只消费结果：P2 的看门狗据此区分
/// 「节点不可达」与「全网不可达」——后者不应触发切换。
pub fn on_network_changed(net_handle: u64, has_internet: bool) {
    NET_HANDLE.store(net_handle, Ordering::Relaxed);
    NET_HAS_INTERNET.store(has_internet, Ordering::Relaxed);
    tracing::info!("[failover] 物理网络变更: handle={net_handle} internet={has_internet}");
}

/// 当前物理网络句柄与 Internet 能力。
pub fn network_state() -> (u64, bool) {
    (
        NET_HANDLE.load(Ordering::Relaxed),
        NET_HAS_INTERNET.load(Ordering::Relaxed),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn node_list_roundtrip() {
        let n = set_node_list(
            r#"[{"uri":"mirage://a@198.51.100.1:443","name":"A"},{"uri":"mirage://b@198.51.100.2:443"}]"#,
        );
        assert_eq!(n, Some(2));
        let list = node_list();
        assert_eq!(list.len(), 2);
        assert_eq!(list[1].name, "", "name 缺省应为空串而非解析失败");
    }

    #[test]
    fn bad_node_json_keeps_previous() {
        set_node_list(r#"[{"uri":"mirage://keep@198.51.100.9:443"}]"#);
        assert_eq!(set_node_list("not json"), None);
        assert_eq!(node_list().len(), 1, "解析失败不得清空既有清单");
    }

    #[test]
    fn policy_defaults_and_partial_json() {
        assert!(set_policy(r#"{"mode":"order"}"#));
        let p = policy();
        assert_eq!(p.mode, "order");
        assert_eq!(p.interval_sec, 15, "缺省间隔应沿用外壳的 15s");
        assert_eq!(p.restart_delay_ms, 3000, "缺省重启延迟应沿用外壳的 3000ms");
    }

    #[test]
    fn bad_policy_json_keeps_previous() {
        set_policy(r#"{"mode":"best","interval_sec":42}"#);
        assert!(!set_policy("{{{"));
        assert_eq!(policy().interval_sec, 42, "解析失败不得改写既有策略");
    }

    #[test]
    fn network_state_roundtrip() {
        on_network_changed(1234, true);
        assert_eq!(network_state(), (1234, true));
        on_network_changed(0, false);
        assert_eq!(network_state(), (0, false));
    }
}
