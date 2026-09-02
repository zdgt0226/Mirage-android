use std::io::Write;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::collections::VecDeque;

pub static GLOBAL_UP: AtomicU64 = AtomicU64::new(0);
pub static GLOBAL_DOWN: AtomicU64 = AtomicU64::new(0);
pub static DROPPED_LOGS: AtomicU64 = AtomicU64::new(0);

pub fn add_up(bytes: u64) {
    GLOBAL_UP.fetch_add(bytes, Ordering::Relaxed);
}

pub fn add_down(bytes: u64) {
    GLOBAL_DOWN.fetch_add(bytes, Ordering::Relaxed);
}

// ── 隧道"卡死"检测 (watchdog failover 用) ────────────────────────────────
// 原理: 隧道流量 (aead 加密层) 每次读写都刷新 TUNNEL_LAST_ACTIVE。若存在活跃的
// 隧道连接 (TCP/UDP/DNS 代理) 却长时间无隧道流量 → 服务器"半死"(连接在但吞包)
// 的强信号。区别于"用户空闲"(无活跃连接时零流量是正常的, 不计卡死)。

/// 最近一次隧道流量时间戳 (unix secs, 0 = 从未)。
static TUNNEL_LAST_ACTIVE: AtomicU64 = AtomicU64::new(0);

fn unix_now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

/// 隧道数据读写经过时调用 (aead 层收发路径)。
pub fn mark_tunnel_active() {
    TUNNEL_LAST_ACTIVE.store(unix_now_secs(), Ordering::Relaxed);
}

/// 距上次隧道流量的秒数。None = 从未有隧道流量 (会话刚开始, 不算卡死)。
pub fn tunnel_stall_secs() -> Option<u64> {
    let last = TUNNEL_LAST_ACTIVE.load(Ordering::Relaxed);
    if last == 0 {
        return None;
    }
    Some(unix_now_secs().saturating_sub(last))
}

/// 当前活跃的**隧道**连接数 (排除直连/拦截)。卡死判定用。
pub fn tunnel_conn_count() -> usize {
    let lock = ACTIVE_CONNECTIONS.lock().unwrap_or_else(|e| e.into_inner());
    match lock.as_ref() {
        Some(map) => map.values().filter(|c| c.outbound.contains("代理")).count(),
        None => 0,
    }
}

// ── 流量采样 (实时速率) ────────────────────────────────────────────────────

/// 最近样本 (时间, 累计上行, 累计下行)。保留 20 个, 间隔 ≥800ms 才记 (过滤 App 高频轮询噪声)。
static RATE_SAMPLES: std::sync::Mutex<VecDeque<(std::time::Instant, u64, u64)>> =
    std::sync::Mutex::new(VecDeque::new());

/// 采样并返回 (up_total, down_total, up_rate_bps, down_rate_bps)。
/// 速率 = 最近两个样本的字节差 / 时间差。
pub fn sample() -> (u64, u64, f64, f64) {
    let now = std::time::Instant::now();
    let (up, down) = (GLOBAL_UP.load(Ordering::Relaxed), GLOBAL_DOWN.load(Ordering::Relaxed));
    let mut q = RATE_SAMPLES.lock().unwrap_or_else(|e| e.into_inner());
    let (mut up_rate, mut down_rate) = (0f64, 0f64);
    if let Some((prev_t, prev_up, prev_dn)) = q.back() {
        let dt = now.duration_since(*prev_t).as_secs_f64();
        if dt >= 0.8 && dt > 0.0 {
            up_rate = (up - prev_up) as f64 / dt;
            down_rate = (down - prev_dn) as f64 / dt;
            q.push_back((now, up, down));
            while q.len() > 20 {
                q.pop_front();
            }
        }
    } else {
        q.push_back((now, up, down));
    }
    (up, down, up_rate, down_rate)
}

#[derive(Clone)]
pub struct MemoryWriter {
    tx: std::sync::mpsc::SyncSender<String>,
    buffer: Arc<Mutex<VecDeque<String>>>,
}

impl Default for MemoryWriter {
    fn default() -> Self {
        Self::new()
    }
}

const LOG_CAP: usize = 2000;

impl MemoryWriter {
    pub fn new() -> Self {
        let (tx, rx) = std::sync::mpsc::sync_channel(2000);
        let buffer = Arc::new(Mutex::new(VecDeque::with_capacity(LOG_CAP)));
        let bg_buf = buffer.clone();
        
        std::thread::spawn(move || {
            while let Ok(s) = rx.recv() {
                let mut q = bg_buf.lock().unwrap_or_else(|e| e.into_inner());
                if q.len() >= LOG_CAP {
                    q.pop_front();
                }
                q.push_back(s);
            }
        });
        
        Self {
            tx,
            buffer,
        }
    }

    /// 追加一行到环形日志。
    pub fn write_line(&self, line: &str) {
        let mut q = self.buffer.lock().unwrap_or_else(|e| e.into_inner());
        if q.len() >= LOG_CAP {
            q.pop_front();
        }
        q.push_back(line.to_string());
    }

    pub fn get_logs(&self) -> Vec<String> {
        let q = self.buffer.lock().unwrap_or_else(|e| e.into_inner());
        q.iter().cloned().collect()
    }

    pub fn drain_logs(&self) -> Vec<String> {
        let mut q = self.buffer.lock().unwrap_or_else(|e| e.into_inner());
        q.drain(..).collect()
    }

    pub fn clear(&self) {
        let mut q = self.buffer.lock().unwrap_or_else(|e| e.into_inner());
        q.clear();
    }
}

impl Write for MemoryWriter {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        let s = String::from_utf8(buf.to_vec())
            .unwrap_or_else(|e| String::from_utf8_lossy(e.as_bytes()).into_owned());
        if s.trim().is_empty() {
            return Ok(buf.len());
        }
        
        if self.tx.try_send(s).is_err() {
            DROPPED_LOGS.fetch_add(1, Ordering::Relaxed);
        }
        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

/// 全局内存日志 (OnceLock, 替代上游 lazy_static)。
pub fn global_logger() -> &'static MemoryWriter {
    use std::sync::OnceLock;
    static LOGGER: OnceLock<MemoryWriter> = OnceLock::new();
    LOGGER.get_or_init(MemoryWriter::new)
}

/// 最近 N 条日志 (App 日志面板用)。
pub fn recent_logs() -> Vec<String> {
    global_logger().get_logs()
}

/// 导出并清空待处理日志 (流式消费语义，规避 Binder 事务溢出)。
pub fn drain_recent_logs() -> Vec<String> {
    global_logger().drain_logs()
}

/// 清空全局内存日志。
pub fn clear_logs() {
    global_logger().clear();
}

/// 追加一行日志 (tracing writer 转发用)。
pub fn recent_logs_push(line: &str) {
    let mut s = line.to_string();
    if s.ends_with('\n') {
        s.pop();
    }
    global_logger().write_line(&s);
}

/// 生成结构化内核诊断快照 JSON (用于一键诊断包导出与离线分析)。
pub fn get_diagnostic_snapshot_json() -> String {
    let (up, down, up_rate, down_rate) = sample();
    let tcp = crate::tun::tcp::TCP_ACTIVE.load(Ordering::Relaxed);
    let udp = crate::tun::udp::flow_count_global();
    let dns = crate::tun::dns::DNS_QUERIES.load(Ordering::Relaxed);
    let stall_secs = tunnel_stall_secs();
    let active_tunnels = tunnel_conn_count();
    let dropped_logs = DROPPED_LOGS.load(Ordering::Relaxed);
    let logs_count = recent_logs().len();

    let snapshot = serde_json::json!({
        "timestamp": unix_now_secs(),
        "stats": {
            "up_total_bytes": up,
            "down_total_bytes": down,
            "up_rate_bps": up_rate,
            "down_rate_bps": down_rate,
            "active_tcp_connections": tcp,
            "active_udp_flows": udp,
            "total_dns_queries": dns,
            "active_tunnel_connections": active_tunnels,
            "tunnel_stall_secs": stall_secs
        },
        "logging": {
            "in_memory_log_count": logs_count,
            "log_buffer_capacity": LOG_CAP,
            "dropped_logs": dropped_logs
        },
        "version": {
            "core_version": env!("CARGO_PKG_VERSION"),
            "protocol_sync": crate::PROTOCOL_SYNC.lines().nth(2).unwrap_or("")
        }
    });

    serde_json::to_string_pretty(&snapshot).unwrap_or_else(|_| "{}".to_string())
}

// ── 活跃连接监控 ────────────────────────────────────────────────────────────

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct ConnectionRecord {
    pub id: u64,
    pub protocol: String,
    pub target: String,
    pub outbound: String,
    pub status: String,
    pub up_bytes: u64,
    pub down_bytes: u64,
    pub start_time: u64,
    pub duration_secs: u64,
}

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct RecentRequestRecord {
    pub id: u64,
    pub protocol: String,       // "TCP", "UDP", "DNS"
    pub target: String,         // "api.github.com:443"
    pub resolved_ip: String,    // "140.82.112.4" or "198.18.0.2 (Fake-IP)"
    pub matched_rule: String,   // "Rule: DOMAIN-SUFFIX (github.com)" or "GEOIP: CN"
    pub outbound: String,       // "PROXY" / "DIRECT" / "BLOCK"
    pub status: String,         // "Active", "Closed (200 OK)", "Closed (Timeout)"
    pub up_bytes: u64,
    pub down_bytes: u64,
    pub start_time: u64,
    pub duration_ms: u64,
    #[serde(default)]
    pub dns_ms: u32,
    #[serde(default)]
    pub connect_ms: u32,
    #[serde(default)]
    pub tls_ms: u32,
    #[serde(default)]
    pub ttfb_ms: u32,
}

pub struct LiveConnection {
    pub id: u64,
    pub protocol: String,
    pub target: String,
    pub outbound: String,
    pub up_bytes: Arc<AtomicU64>,
    pub down_bytes: Arc<AtomicU64>,
    pub start_time: u64,
    pub abort: Arc<tokio::sync::Notify>,
}

const RECENT_REQUESTS_CAP: usize = 300;
static NEXT_CONN_ID: AtomicU64 = AtomicU64::new(1);
static ACTIVE_CONNECTIONS: Mutex<Option<std::collections::HashMap<u64, LiveConnection>>> = Mutex::new(None);
static RECENT_REQUESTS: Mutex<Option<VecDeque<RecentRequestRecord>>> = Mutex::new(None);

/// 注册新连接，返回 (id, up_atomic, down_atomic, abort_notify)。
/// 读写协程可直接原子累加，无需争抢全局大锁，实现实时无锁流量统计与定向中断。
pub fn record_conn_start(
    protocol: &str,
    target: &str,
    resolved_ip: &str,
    matched_rule: &str,
    outbound: &str,
) -> (u64, Arc<AtomicU64>, Arc<AtomicU64>, Arc<tokio::sync::Notify>) {
    let id = NEXT_CONN_ID.fetch_add(1, Ordering::Relaxed);
    let start_time = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();

    let up_bytes = Arc::new(AtomicU64::new(0));
    let down_bytes = Arc::new(AtomicU64::new(0));
    let abort = Arc::new(tokio::sync::Notify::new());

    let record = LiveConnection {
        id,
        protocol: protocol.to_string(),
        target: target.to_string(),
        outbound: outbound.to_string(),
        up_bytes: up_bytes.clone(),
        down_bytes: down_bytes.clone(),
        start_time,
        abort: abort.clone(),
    };

    let req_item = RecentRequestRecord {
        id,
        protocol: protocol.to_string(),
        target: target.to_string(),
        resolved_ip: resolved_ip.to_string(),
        matched_rule: matched_rule.to_string(),
        outbound: outbound.to_string(),
        status: "Active".to_string(),
        up_bytes: 0,
        down_bytes: 0,
        start_time,
        duration_ms: 0,
        dns_ms: 0,
        connect_ms: 0,
        tls_ms: 0,
        ttfb_ms: 0,
    };

    {
        let mut lock = ACTIVE_CONNECTIONS.lock().unwrap_or_else(|e| e.into_inner());
        let map = lock.get_or_insert_with(|| std::collections::HashMap::with_capacity(128));
        map.insert(id, record);
    }
    {
        let mut q_lock = RECENT_REQUESTS.lock().unwrap_or_else(|e| e.into_inner());
        let q = q_lock.get_or_insert_with(|| VecDeque::with_capacity(RECENT_REQUESTS_CAP));
        if q.len() >= RECENT_REQUESTS_CAP {
            q.pop_back();
        }
        q.push_front(req_item);
    }

    (id, up_bytes, down_bytes, abort)
}

/// 记录连接关键阶段耗时瀑布流指标 (DNS 解析、TCP 握手/建连、TLS 协商、首字节响应 TTFB)
pub fn record_conn_timings(id: u64, dns_ms: u32, connect_ms: u32, tls_ms: u32, ttfb_ms: u32) {
    let mut q_lock = RECENT_REQUESTS.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(q) = q_lock.as_mut() {
        if let Some(item) = q.iter_mut().find(|r| r.id == id) {
            if dns_ms > 0 { item.dns_ms = dns_ms; }
            if connect_ms > 0 { item.connect_ms = connect_ms; }
            if tls_ms > 0 { item.tls_ms = tls_ms; }
            if ttfb_ms > 0 { item.ttfb_ms = ttfb_ms; }
        }
    }
}

/// 定向中断并关闭指定 ID 的活跃连接 (对齐 DELETE /connections/{id})
pub fn close_connection(id: u64) -> bool {
    let lock = ACTIVE_CONNECTIONS.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(map) = lock.as_ref() {
        if let Some(c) = map.get(&id) {
            c.abort.notify_waiters();
            return true;
        }
    }
    false
}

/// 批量中断并重置所有活跃连接 (对齐 DELETE /connections)
pub fn close_all_connections() -> usize {
    let lock = ACTIVE_CONNECTIONS.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(map) = lock.as_ref() {
        let count = map.len();
        for c in map.values() {
            c.abort.notify_waiters();
        }
        count
    } else {
        0
    }
}

/// 关闭连接：从活跃连接列表中彻底移除，并在 Recent Requests 队列中标记完成状态与精准耗时。
pub fn record_conn_close_with_duration(id: u64, up: u64, down: u64, status: &str, duration_ms: u64) {
    {
        let mut lock = ACTIVE_CONNECTIONS.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(map) = lock.as_mut() {
            map.remove(&id);
        }
    }

    {
        let mut q_lock = RECENT_REQUESTS.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(q) = q_lock.as_mut() {
            if let Some(item) = q.iter_mut().find(|r| r.id == id) {
                item.up_bytes = up;
                item.down_bytes = down;
                item.duration_ms = duration_ms;
                item.status = status.to_string();
            }
        }
    }
}

pub fn record_conn_close(id: u64, up: u64, down: u64, status: &str) {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();

    let mut start_time = now;
    {
        let lock = ACTIVE_CONNECTIONS.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(map) = lock.as_ref() {
            if let Some(live) = map.get(&id) {
                start_time = live.start_time;
            }
        }
    }
    let duration_ms = now.saturating_sub(start_time) * 1000;
    record_conn_close_with_duration(id, up, down, status, duration_ms);
}

/// 获取当前所有活跃连接的实时 JSON 快照
pub fn get_connections_json() -> String {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    let lock = ACTIVE_CONNECTIONS.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(map) = lock.as_ref() {
        let mut list: Vec<ConnectionRecord> = map
            .values()
            .map(|c| ConnectionRecord {
                id: c.id,
                protocol: c.protocol.clone(),
                target: c.target.clone(),
                outbound: c.outbound.clone(),
                status: "已连接".to_string(),
                up_bytes: c.up_bytes.load(Ordering::Relaxed),
                down_bytes: c.down_bytes.load(Ordering::Relaxed),
                start_time: c.start_time,
                duration_secs: now.saturating_sub(c.start_time),
            })
            .collect();
        // 按照最新的连接排在前面
        list.sort_by(|a, b| b.id.cmp(&a.id));
        if list.len() > 100 {
            list.truncate(100);
        }
        serde_json::to_string(&list).unwrap_or_else(|_| "[]".to_string())
    } else {
        "[]".to_string()
    }
}

/// 获取 Surge 级 Recent Requests 请求流列表 (最新排前，包含已关闭与活跃请求)
pub fn get_recent_requests_json() -> String {
    let q_lock = RECENT_REQUESTS.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(q) = q_lock.as_ref() {
        let list: Vec<&RecentRequestRecord> = q.iter().collect();
        serde_json::to_string(&list).unwrap_or_else(|_| "[]".to_string())
    } else {
        "[]".to_string()
    }
}
