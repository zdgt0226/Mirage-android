use crate::crypto::aead::{create_crypto_pair, create_crypto_pair_pfs, CryptoReader, CryptoWriter};
use crate::proxy::outbound::{Address, OutboundNode};
use crate::proxy::tunnel::{Tunnel, TunnelRead, TunnelWrite};
use anyhow::Result;
use std::collections::VecDeque;
use std::sync::atomic::AtomicU64;
use std::sync::Arc;
use std::sync::RwLock;
use tokio::sync::{Mutex, Notify};
use tokio::time::Instant;
use tracing::{debug, error, info};

pub struct PoolConfig {
    pub server_host: String,
    pub server_port: u16,
    pub password: String,
    pub camouflage_host: String,
    pub pool_size: usize,
    /// 链式代理: 有则本 Mirage 隧道对 server 的连接经此出站拨号 (Mirage-over-X), 而非物理 TCP。
    /// OutboundManager 构建时解析 config 的 `underlying` tag 注入。None = 直连 (默认)。
    pub underlying: Option<Arc<OutboundNode>>,
    /// 前向保密: 握手做一次性 X25519 ECDH (见 crypto::pfs)。须与服务端 pfs 同开。默认 false。
    pub pfs: bool,
}

/**
 * [Brutal 拥塞控制状态机]
 * 维护每个节点出站连接的动态拥塞控制参数。
 * 它根据 eBPF 提取的 TCP RTT 和丢包情况动态调节传输速率（BDP 算法）。
 */
pub struct BrutalState {
    pub configured_rate: Option<u64>,
    pub current_rate: Arc<std::sync::atomic::AtomicU64>, // 当前动态调整的发送速率
    pub base_rtt: Option<u64>,                           // 连接池基准 RTT (测得的最快延迟)
    pub active_fds: Arc<std::sync::Mutex<std::collections::HashSet<i32>>>, // 正在传输数据的套接字文件描述符集合
}

/**
 * [活跃连接守卫 (RAII Guard)]
 * 用于自动管理 active_fds 集合的生命周期。
 * 创建时外部将其 FD 加入集合；当作用域结束（Guard 销毁）时，利用 Drop trait 自动将 FD 移出集合。
 * 这是防止死 FD 泄漏并干扰拥塞控制算法的核心安全机制。
 */
pub struct ActiveFdGuard {
    state: Arc<BrutalState>,
    fd: i32,
}

impl Drop for ActiveFdGuard {
    fn drop(&mut self) {
        if let Ok(mut lock) = self.state.active_fds.lock() {
            lock.remove(&self.fd);
        }
    }
}

/// 标识上游握手/认证类失败 (区别于普通物理网络 IO 错误)。
/// 用于 WarmPool 限制连续认证失败时的后台高频重试耗电。
#[derive(Debug)]
pub struct AuthFailure(pub String);

impl std::fmt::Display for AuthFailure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl std::error::Error for AuthFailure {}

pub const AUTH_PAUSE_DURATION: Duration = Duration::from_secs(300); // 5 minutes
pub const AUTH_FAILURES_THRESHOLD: u32 = 5;

/// 认证失败暂停状态跟踪器 (可注入时间进行纯逻辑测试)
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AuthPauseTracker {
    pub consecutive_auth_failures: u32,
    pub paused_at: Option<Instant>,
}

impl Default for AuthPauseTracker {
    fn default() -> Self {
        Self::new()
    }
}

impl AuthPauseTracker {
    pub const fn new() -> Self {
        Self {
            consecutive_auth_failures: 0,
            paused_at: None,
        }
    }

    /// 记录一次认证失败。
    /// 若连续认证失败达到阈值 (>= 5 次) 且未处于暂停中，进入暂停状态并返回 true (表示在此次调用中刚触发暂停)。
    pub fn record_auth_failure(&mut self, now: Instant) -> bool {
        self.consecutive_auth_failures += 1;
        if self.consecutive_auth_failures >= AUTH_FAILURES_THRESHOLD && self.paused_at.is_none() {
            self.paused_at = Some(now);
            true
        } else {
            false
        }
    }

    /// 记录一次非认证失败：清零连续认证失败计数 (非认证错误中断连续序列)。
    pub fn record_non_auth_failure(&mut self) {
        self.consecutive_auth_failures = 0;
    }

    /// 记录成功建连：清零计数并解除暂停。若此前处于暂停中，返回 true。
    pub fn record_success(&mut self) -> bool {
        self.consecutive_auth_failures = 0;
        self.paused_at.take().is_some()
    }

    /// purge_idle 触发：清零计数并解除暂停。若此前处于暂停中，返回 true。
    pub fn purge(&mut self) -> bool {
        self.consecutive_auth_failures = 0;
        self.paused_at.take().is_some()
    }

    /// 检查并推进暂停状态。若暂停已满 5 分钟则自动解除并清零计数。
    /// 返回 `(is_paused, just_expired)`。
    pub fn check_paused(&mut self, now: Instant) -> (bool, bool) {
        if let Some(t) = self.paused_at {
            if now.saturating_duration_since(t) >= AUTH_PAUSE_DURATION {
                self.paused_at = None;
                self.consecutive_auth_failures = 0;
                (false, true)
            } else {
                (true, false)
            }
        } else {
            (false, false)
        }
    }

    /// 查询当前是否处于暂停中
    pub fn is_paused(&self, now: Instant) -> bool {
        if let Some(t) = self.paused_at {
            now.saturating_duration_since(t) < AUTH_PAUSE_DURATION
        } else {
            false
        }
    }
}

/// 计算建连失败后的指数退避时间:
/// `base = 500ms * 2^(failures - 1)`, 上限 60s, 加 ±20% 抖动 (fastrand)。
/// failures = 0 时返回 Duration::ZERO。
pub fn builder_backoff(failures: u32) -> Duration {
    if failures == 0 {
        return Duration::ZERO;
    }
    let shift = (failures - 1).min(10);
    let base_ms = (500u64.saturating_mul(1u64 << shift)).min(60_000);
    let jitter_min = base_ms * 80 / 100;
    let jitter_max = base_ms * 120 / 100;
    Duration::from_millis(fastrand::u64(jitter_min..=jitter_max))
}

#[derive(Debug)]
pub struct PoolStats {
    pub latency_samples: VecDeque<u64>,
    pub consecutive_failures: u32,
    pub consecutive_auth_failures: u32,
    pub auth_tracker: AuthPauseTracker,
    pub last_sample_time: Option<Instant>,
}

impl Default for PoolStats {
    fn default() -> Self {
        Self::new()
    }
}

impl PoolStats {
    pub fn new() -> Self {
        Self {
            latency_samples: VecDeque::with_capacity(10),
            consecutive_failures: 0,
            consecutive_auth_failures: 0,
            auth_tracker: AuthPauseTracker::new(),
            last_sample_time: None,
        }
    }

    pub fn record_latency(&mut self, ms: u64) -> bool {
        if self.latency_samples.len() == 10 {
            self.latency_samples.pop_front();
        }
        self.latency_samples.push_back(ms);
        self.last_sample_time = Some(Instant::now());
        self.consecutive_failures = 0;
        let unpaused = self.auth_tracker.record_success();
        self.consecutive_auth_failures = self.auth_tracker.consecutive_auth_failures;
        unpaused
    }

    pub fn record_failure(&mut self) {
        self.record_failure_typed(false);
    }

    pub fn record_failure_typed(&mut self, is_auth: bool) -> bool {
        self.consecutive_failures += 1;
        let just_paused = if is_auth {
            self.auth_tracker.record_auth_failure(Instant::now())
        } else {
            self.auth_tracker.record_non_auth_failure();
            false
        };
        self.consecutive_auth_failures = self.auth_tracker.consecutive_auth_failures;
        just_paused
    }

    pub fn purge_auth_pause(&mut self) -> bool {
        let unpaused = self.auth_tracker.purge();
        self.consecutive_auth_failures = self.auth_tracker.consecutive_auth_failures;
        unpaused
    }

    pub fn check_auth_pause(&mut self, now: Instant) -> (bool, bool) {
        let (paused, expired) = self.auth_tracker.check_paused(now);
        self.consecutive_auth_failures = self.auth_tracker.consecutive_auth_failures;
        (paused, expired)
    }

    pub fn is_auth_paused(&self, now: Instant) -> bool {
        self.auth_tracker.is_paused(now)
    }

    pub fn latency_ms(&self) -> Option<u64> {
        if self.latency_samples.is_empty() {
            return None;
        }
        let mut sorted: Vec<u64> = self.latency_samples.iter().copied().collect();
        sorted.sort_unstable();
        Some(sorted[sorted.len() / 2])
    }

    pub fn is_healthy(&self) -> bool {
        self.consecutive_failures < 3
    }
}

/// WarmPool 反馈式弹性算法的运行时指标 (v0.4.2+).
///
/// 替代旧的 `RPS * 3 + 2` 开环算法. 每 5 秒由 Manager task 读取并归零,
/// 根据 (wait_ratio, expired/total_gets) 做 AIAD 调节. 详见 decide_new_target.
pub struct PoolMetrics {
    /// pool.get() 等待 > 50ms 才拿到 tunnel 的次数. 反映"供给不足"压力.
    pub wait_events: AtomicU64,
    /// pool.get() 总调用数 (周期内).
    pub total_gets: AtomicU64,
    /// Sweeper 在 max_age 到期前没被 get 用过的 tunnel 数. 反映"建多了没人用".
    pub expired_unused: AtomicU64,
}

impl PoolMetrics {
    fn new() -> Self {
        Self {
            wait_events: AtomicU64::new(0),
            total_gets: AtomicU64::new(0),
            expired_unused: AtomicU64::new(0),
        }
    }
}

/// 反馈式 target 决策 — 纯函数, 便于单元测试.
///
/// AIAD (additive increase, additive decrease) 控制:
/// - 上一周期有 > 20% 的 get 经历过 50ms+ 等待 → target 加 20% (最少 +1)
/// - 上一周期 0 个 wait_event AND 过期未用 ≥ 一半使用数 → target 减 1
/// - 否则维持
///
/// 不做硬裁剪 (旧版 q.len() > target+2 那段已删), target 只控制 builder 建货
/// 节奏, queue 自然在 max_age 到期被 sweeper 收掉.
/// WarmPool 缩容底线. 突发 N 并发请求时 pool 至少要有 N 条常温 tunnel,
/// 否则 N-target 条会 wait build (770-2000ms 每条), 用户感受"卡顿".
///
/// alpha.10 之前是 2, 实测浏览器 YouTube 突发经常 6 并发, 66% wait.
/// 提到 10 = 常见浏览器并发上限, 突发时立刻各分 1 条 tunnel 无 wait.
///
/// 反馈式 target 决策: 维持用户配置的 pool_size 预热容量。
pub(crate) fn decide_new_target(
    cur_target: usize,
    wait_events: u64,
    total_gets: u64,
    expired_unused: u64,
    max_size: usize,
) -> usize {
    let wait_ratio = if total_gets == 0 {
        0.0
    } else {
        wait_events as f64 / total_gets as f64
    };
    let floor = (max_size / 2).max(2).min(max_size);

    if wait_ratio > 0.2 && cur_target < max_size {
        let increment = (cur_target / 5).max(1);
        (cur_target + increment).min(max_size)
    } else if wait_ratio == 0.0 && expired_unused >= total_gets / 2 && cur_target > floor {
        cur_target - 1
    } else {
        cur_target.max(floor).min(max_size)
    }
}

#[cfg(test)]
mod feedback_tests {
    use super::*;

    #[test]
    fn idle_at_floor_stays() {
        assert_eq!(decide_new_target(16, 0, 0, 0, 32), 16);
        assert_eq!(decide_new_target(2, 0, 0, 0, 4), 2);
    }

    #[test]
    fn pressure_scales_up() {
        assert_eq!(decide_new_target(5, 3, 10, 0, 50), 6);
        assert_eq!(decide_new_target(10, 3, 10, 0, 50), 12);
    }

    #[test]
    fn pressure_clamped_by_max() {
        assert_eq!(decide_new_target(50, 5, 10, 0, 50), 50);
        assert_eq!(decide_new_target(48, 5, 10, 0, 50), 50);
    }

    #[tokio::test]
    async fn hot_reload_pool_size() {
        let cfg = Arc::new(PoolConfig {
            server_host: "127.0.0.1".to_string(),
            server_port: 8443,
            password: "test".to_string(),
            camouflage_host: "example.com".to_string(),
            pool_size: 8,
            underlying: None,
            pfs: false,
        });
        let bs = Arc::new(BrutalState {
            configured_rate: None,
            current_rate: Arc::new(AtomicU64::new(0)),
            base_rtt: None,
            active_fds: Arc::new(std::sync::Mutex::new(std::collections::HashSet::new())),
        });
        let pool = WarmPool::new(cfg, bs);
        assert_eq!(pool.get_pool_size(), 8);

        pool.set_pool_size(32);
        assert_eq!(pool.get_pool_size(), 32);

        pool.set_pool_size(4);
        assert_eq!(pool.get_pool_size(), 4);
    }

    #[tokio::test]
    async fn warm_pool_shutdown_stops_cleanly() {
        let cfg = Arc::new(PoolConfig {
            server_host: "127.0.0.1".to_string(),
            server_port: 8443,
            password: "test".to_string(),
            camouflage_host: "example.com".to_string(),
            pool_size: 4,
            underlying: None,
            pfs: false,
        });
        let bs = Arc::new(BrutalState {
            configured_rate: None,
            current_rate: Arc::new(AtomicU64::new(0)),
            base_rtt: None,
            active_fds: Arc::new(std::sync::Mutex::new(std::collections::HashSet::new())),
        });
        let pool = WarmPool::new(cfg, bs);
        assert!(!pool.shutdown.load(Ordering::Relaxed));

        pool.shutdown();
        assert!(pool.shutdown.load(Ordering::Relaxed));
        assert_eq!(pool.queue.lock().await.len(), 0);
    }

    #[test]
    fn test_builder_backoff() {
        // failures = 0 为 0
        assert_eq!(builder_backoff(0), Duration::ZERO);

        // 单调性: ±20% 抖动下相邻失败等级区间严格不重叠, 故单调递增
        for _ in 0..100 {
            for f in 1..=7 {
                let d1 = builder_backoff(f);
                let d2 = builder_backoff(f + 1);
                assert!(d1 < d2, "f={f} expected {d1:?} < {d2:?}");
            }
        }

        // 上限: base 最大 60s, +20% 抖动后最大不超过 72s
        for f in [8, 10, 50, 100] {
            let d = builder_backoff(f);
            assert!(d <= Duration::from_millis(72_000), "exceeded 72s: {d:?}");
            assert!(d >= Duration::from_millis(48_000), "below 48s: {d:?}");
        }
    }

    #[test]
    fn test_auth_pause_tracker_state_machine() {
        let mut tracker = AuthPauseTracker::new();
        let t0 = Instant::now();

        // 1..4 次认证失败: 不进入暂停
        for i in 1..=4 {
            assert!(!tracker.record_auth_failure(t0));
            assert_eq!(tracker.consecutive_auth_failures, i);
            assert!(!tracker.is_paused(t0));
            let (paused, _) = tracker.check_paused(t0);
            assert!(!paused);
        }

        // 第 5 次认证失败: 触发暂停
        assert!(tracker.record_auth_failure(t0));
        assert_eq!(tracker.consecutive_auth_failures, 5);
        assert!(tracker.is_paused(t0));
        let (paused, _) = tracker.check_paused(t0);
        assert!(paused);

        // 4 分钟时仍处于暂停
        let t_4m = t0 + Duration::from_secs(240);
        assert!(tracker.is_paused(t_4m));
        let (paused, expired) = tracker.check_paused(t_4m);
        assert!(paused);
        assert!(!expired);

        // 5 分钟超时: 自动恢复并清零计数
        let t_5m = t0 + Duration::from_secs(300);
        assert!(!tracker.is_paused(t_5m));
        let (paused, expired) = tracker.check_paused(t_5m);
        assert!(!paused);
        assert!(expired);
        assert_eq!(tracker.consecutive_auth_failures, 0);

        // 再次触发暂停后, purge_idle 立即恢复
        for _ in 0..5 {
            tracker.record_auth_failure(t0);
        }
        assert!(tracker.is_paused(t0));
        assert!(tracker.purge());
        assert!(!tracker.is_paused(t0));
        assert_eq!(tracker.consecutive_auth_failures, 0);

        // 再次触发暂停后, 建连成功 (record_success) 立即恢复
        for _ in 0..5 {
            tracker.record_auth_failure(t0);
        }
        assert!(tracker.is_paused(t0));
        assert!(tracker.record_success());
        assert!(!tracker.is_paused(t0));
        assert_eq!(tracker.consecutive_auth_failures, 0);

        // 非认证错误中断连续认证失败计数
        for _ in 0..4 {
            tracker.record_auth_failure(t0);
        }
        assert_eq!(tracker.consecutive_auth_failures, 4);
        tracker.record_non_auth_failure();
        assert_eq!(tracker.consecutive_auth_failures, 0);
        assert!(!tracker.record_auth_failure(t0));
        assert_eq!(tracker.consecutive_auth_failures, 1);
        assert!(!tracker.is_paused(t0));
    }
}

use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::time::timeout;

/// 客户端伪装握手产物: 会话 salt + 可选 PFS ECDH 共享秘密。
struct ClientHandshake {
    client_random: [u8; 32],
    /// ServerHello.random: v0.15 起参与会话 master 派生 (服务端新鲜性), PFS 下即服务端临时公钥。
    server_random: [u8; 32],
    /// PFS 开时 = 与服务端临时公钥 ECDH 出的共享秘密; 关时 None。
    ecdh: Option<[u8; 32]>,
}

/// 处理接收到的 TIME_SYNC 帧数据, 返回是否启用 cipher agility (bool)。
/// 抽成独立函数便于单元测试 (不依赖网络 IO)。
pub(crate) fn process_time_sync_frame(data: &[u8]) -> anyhow::Result<bool> {
    if data.len() == 10
        && data[0] == 0x01
        && (data[1] == crate::crypto::cipher::PROTO_VER_LEGACY
            || data[1] == crate::crypto::cipher::PROTO_VER_AGILITY)
    {
        let server_agility = data[1] == crate::crypto::cipher::PROTO_VER_AGILITY;
        let server_time = u64::from_be_bytes(data[2..10].try_into().unwrap());
        crate::time_sync::set_offset_from_server_time(server_time);
        Ok(server_agility)
    } else {
        tracing::warn!(
            "TIME_SYNC: unexpected frame (len={}, type={:?})",
            data.len(),
            data.first()
        );
        Err(AuthFailure(format!(
            "TIME_SYNC 非预期帧 (len={}, type={:?})",
            data.len(),
            data.first()
        ))
        .into())
    }
}

/// 服务端握手返回的元数据: 包含 ServerHello.random 与协商出的 cipher_suite。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ServerHandshake {
    pub server_random: [u8; 32],
    pub cipher_suite: u16,
}

/// 读服务端 flight (ServerHello + CCS + 加密段), 返回捕获的 **ServerHello.random** 与 **cipher_suite**。
///
/// PFS 下 server_random = 服务端临时 X25519 公钥 (见 crypto::pfs); 非 PFS 下参与会话密钥派生。
/// 仍要求集齐 0x16+0x14+0x17 三型才成功 (见 handshake-template-completeness)。若 server_random
/// 全 0 则必须 fail-closed 报错断开。
pub async fn read_server_handshake<R: tokio::io::AsyncRead + Unpin>(
    stream: &mut R,
) -> Result<ServerHandshake> {
    // v0.4.5-alpha.17: 放弃超时随机化, 消除固定 12s/1.5s 阈值的客户端时序指纹.
    // GFW 若主动操纵服务端响应时序 (拦截/延迟 ServerHello) 测客户端恒定放弃时间可
    // 识别 Mirage 客户端. 每连接各随机一次 (非每轮, 保持单次握手内一致), 围绕原值
    // 抖动: pre-CCS 10~14s, post-CCS 1.2~1.8s —— 仍足够宽容真实慢链路, 但不再恒定.
    let pre_ccs_timeout = Duration::from_millis(10_000 + fastrand::u64(0..=4_000));
    let post_ccs_timeout = Duration::from_millis(1_200 + fastrand::u64(0..=600));

    let mut saw_sh = false;
    let mut saw_ccs = false;
    let mut saw_enc = false;
    // ServerHello.random (record body[6..38]): PFS 下即服务端临时公钥。首个 0x16 时捕获。
    let mut server_random = [0u8; 32];
    let mut cipher_suite = 0x1301u16;

    loop {
        let t = if saw_ccs {
            post_ccs_timeout
        } else {
            pre_ccs_timeout
        };
        let mut header = [0u8; 5];
        match timeout(t, stream.read_exact(&mut header)).await {
            Ok(Ok(_)) => {
                let ct = header[0];
                let length = u16::from_be_bytes([header[3], header[4]]) as usize;

                let mut body = vec![0u8; length];
                match timeout(t, stream.read_exact(&mut body)).await {
                    Ok(Ok(_)) => {
                        if ct == 0x15 {
                            return Err(AuthFailure("Server sent TLS alert".to_string()).into());
                        } else if ct == 0x16 {
                            // 首个 ServerHello: 捕获 random (body[6..38]) 与 cipher_suite。ServerHello body 布局:
                            // [0x02 type][3B len][2B version][32B random][1B sid_len][sid][2B cipher]...
                            if !saw_sh && body.len() >= 38 {
                                server_random.copy_from_slice(&body[6..38]);
                                if body.len() >= 39 {
                                    let sid_len = body[38] as usize;
                                    if body.len() >= 39 + sid_len + 2 {
                                        cipher_suite = u16::from_be_bytes([
                                            body[39 + sid_len],
                                            body[40 + sid_len],
                                        ]);
                                    }
                                }
                            }
                            saw_sh = true;
                        } else if ct == 0x14 {
                            saw_ccs = true;
                        } else if ct == 0x17 {
                            saw_enc = true;
                        }

                        if saw_sh && saw_ccs && saw_enc {
                            if server_random == [0u8; 32] {
                                return Err(anyhow::anyhow!("未能捕获有效的 ServerHello.random (全 0), 握手失败断开 (fail-closed)"));
                            }
                            return Ok(ServerHandshake {
                                server_random,
                                cipher_suite,
                            });
                        }
                    }
                    Ok(Err(e)) => return Err(anyhow::anyhow!("Incomplete body: {}", e)),
                    Err(_) => return Err(anyhow::anyhow!("Timeout reading body")),
                }
            }
            Ok(Err(e)) => return Err(anyhow::anyhow!("Incomplete header: {}", e)),
            Err(_) => {
                if !saw_ccs {
                    return Err(anyhow::anyhow!("Timeout before CCS"));
                }
                break; // normal exit after timeout if we saw CCS
            }
        }
    }

    if !saw_sh || !saw_ccs || !saw_enc {
        return Err(anyhow::anyhow!(
            "Incomplete flight: sh={}, ccs={}, enc={}",
            saw_sh,
            saw_ccs,
            saw_enc
        ));
    }
    if server_random == [0u8; 32] {
        return Err(anyhow::anyhow!(
            "未能捕获有效的 ServerHello.random (全 0), 握手失败断开 (fail-closed)"
        ));
    }
    Ok(ServerHandshake {
        server_random,
        cipher_suite,
    })
}

use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

/// [弹性预热连接池 (WarmPool)]
/// Mirage 的核心性能组件，用于零延迟转发。
///
/// 工作原理：
/// 1. 后台异步维护一个处于 TLS 握手完毕状态的空闲隧道队列。
/// 2. 客户端请求到达时，直接从池中取出一个已经建好握手的 Tunnel。
/// 3. 并发不足时弹性扩容，支持高并发无缝爆发。
pub struct WarmPool {
    queue: Arc<Mutex<VecDeque<Tunnel>>>, // 空闲可用的隧道队列
    notify: Arc<Notify>,                 // 阻塞唤醒器（当没有连接时挂起请求）
    pub stats: Arc<RwLock<PoolStats>>,   // 连接池的延迟统计和健康检查
    pub brutal_state: Arc<BrutalState>,  // 该连接池绑定的拥塞控制状态
    metrics: Arc<PoolMetrics>,           // 反馈式弹性算法的运行时指标
    target_size: Arc<AtomicUsize>,       // 动态目标容量 (支持热重载)
    max_size: Arc<AtomicUsize>,          // 动态最大容量 (支持热重载)
    cfg: Arc<PoolConfig>,                // 节点配置 (支持饥饿时 On-Demand 即时并发拨号)
    pub shutdown: Arc<AtomicBool>,       // 安全关闭标志 (节点切换/引擎替换时终止后台协程与建连)
    /// On-Demand 并发拨号限流信号量: 防止瞬时突发 (如 20 张图片) 同时发起 20 条 TLS
    /// 握手 (thundering herd)。上限取 clamp(pool_size, 4, 16), 兼顾图片秒开与平滑。
    on_demand_sem: Arc<tokio::sync::Semaphore>,
}

impl WarmPool {
    pub fn new(cfg: Arc<PoolConfig>, brutal_state: Arc<BrutalState>) -> Self {
        let initial_size = cfg.pool_size.max(1);
        let queue = Arc::new(Mutex::new(VecDeque::with_capacity(initial_size)));
        let notify = Arc::new(Notify::new());
        let stats = Arc::new(RwLock::new(PoolStats::new()));
        let metrics = Arc::new(PoolMetrics::new());
        let target_size = Arc::new(AtomicUsize::new(initial_size));
        let max_size = Arc::new(AtomicUsize::new(initial_size));
        let shutdown = Arc::new(AtomicBool::new(false));

        // 并发拨号上限: 至少 16 (图片秒开), 至多 32 (防 thundering herd)。
        // 平滑靠回流 + notify (见 refill_or_take), 而非压低并发 —— 太低的并发
        // (如 4/8) 会让 20 张图片排队成渐进延迟 (实测 1.6~8s), 违背"秒开"初衷。
        let on_demand_limit = initial_size.clamp(16, 32);
        let pool = Self {
            queue: queue.clone(),
            notify: notify.clone(),
            stats: stats.clone(),
            brutal_state: brutal_state.clone(),
            metrics: metrics.clone(),
            target_size: target_size.clone(),
            max_size: max_size.clone(),
            cfg: cfg.clone(),
            shutdown: shutdown.clone(),
            on_demand_sem: Arc::new(tokio::sync::Semaphore::new(on_demand_limit)),
        };

        let in_flight = Arc::new(AtomicUsize::new(0));

        // 弹性监控协程 (Manager Task) — 反馈式 v0.4.2+
        // 每 5s 读 metrics 决定 target 调整 + 顺手清理 max_age 过期连接.
        let metrics_clone = metrics.clone();
        let target_clone = target_size.clone();
        let max_size_mgr = max_size.clone();
        let q_clone = queue.clone();
        let in_flight_clone_mgr = in_flight.clone();
        let shutdown_mgr = shutdown.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(Duration::from_secs(5)).await;
                if shutdown_mgr.load(Ordering::Relaxed) {
                    break;
                }

                // 读三个计数器并归零, 进入下一周期
                let wait = metrics_clone.wait_events.swap(0, Ordering::Relaxed);
                let gets = metrics_clone.total_gets.swap(0, Ordering::Relaxed);

                // expired_unused 在 sweeper 内累加, 这里读后归零
                let cur_target = target_clone.load(Ordering::Relaxed);
                let current_max = max_size_mgr.load(Ordering::Relaxed);

                // 清理 max_age 过期的 tunnel (顺手 close_notify), 同时统计 expired_unused
                let mut q = q_clone.lock().await;
                let mut to_drop = Vec::new();
                let mut alive = std::collections::VecDeque::with_capacity(q.len());
                for tunnel in q.drain(..) {
                    if tunnel.created_at.elapsed().as_secs() > tunnel.max_age_sec {
                        to_drop.push(tunnel);
                    } else {
                        alive.push_back(tunnel);
                    }
                }
                *q = alive;
                let expired_now = to_drop.len() as u64;
                metrics_clone
                    .expired_unused
                    .fetch_add(expired_now, Ordering::Relaxed);
                let expired_total = metrics_clone.expired_unused.swap(0, Ordering::Relaxed);

                const EXPIRING_THRESHOLD_SEC: u64 = 10;
                let idle_count = q.len();
                let expiring_soon = q
                    .iter()
                    .filter(|t| {
                        let elapsed = t.created_at.elapsed().as_secs();
                        let remaining = t.max_age_sec.saturating_sub(elapsed);
                        remaining < EXPIRING_THRESHOLD_SEC
                    })
                    .count();
                drop(q);
                let in_flight_now = in_flight_clone_mgr.load(Ordering::Relaxed);

                for mut tunnel in to_drop {
                    tokio::spawn(async move {
                        let _ = tunnel.writer.send_close_notify().await;
                        tracing::trace!("WarmPool Manager: Closed max_age-expired tunnel.");
                    });
                }

                // 反馈式 target 决策 (纯函数)
                let new_target =
                    decide_new_target(cur_target, wait, gets, expired_total, current_max);
                if new_target != cur_target {
                    target_clone.store(new_target, Ordering::Relaxed);
                }
                let wait_ratio = if gets == 0 {
                    0.0
                } else {
                    wait as f64 / gets as f64
                };
                let target_display = if new_target != cur_target {
                    format!("{}→{}", cur_target, new_target)
                } else {
                    format!("{}", cur_target)
                };
                debug!(
                    "WarmPool: target={} [idle={} inflight={} exp<{}s={}] gets={} wait={}({:.1}%) expired={}",
                    target_display,
                    idle_count,
                    in_flight_now,
                    EXPIRING_THRESHOLD_SEC,
                    expiring_soon,
                    gets,
                    wait,
                    wait_ratio * 100.0,
                    expired_total
                );
            }
        });

        // 连接补充协程 (Builder Task)
        let q_clone_builder = queue.clone();
        let n_clone_builder = notify.clone();
        let cfg_clone = cfg.clone();
        let in_flight_clone = in_flight.clone();
        let target_clone_builder = target_size.clone();
        let max_size_builder = max_size.clone();
        let stats_builder = stats.clone();
        let brutal_state_builder = brutal_state.clone();
        let shutdown_builder = shutdown.clone();

        tokio::spawn(async move {
            info!("WarmPool (Elastic) initialized. Capacity: {}", initial_size);
            let mut next_build_at = Instant::now();

            loop {
                if shutdown_builder.load(Ordering::Relaxed) {
                    break;
                }

                // 认证失败频控暂停: 连续认证失败 >= 5 次时暂停后台补池,
                // 直到 purge_idle 触发自愈或满 5 分钟超时恢复
                let is_paused = {
                    let mut stats = stats_builder.write().unwrap_or_else(|e| e.into_inner());
                    let (paused, expired) = stats.check_auth_pause(Instant::now());
                    if expired {
                        tracing::info!("WarmPool: 连续认证失败暂停已满 5 分钟, 自动恢复后台预热");
                    }
                    paused
                };
                if is_paused {
                    tokio::time::sleep(Duration::from_millis(500)).await;
                    continue;
                }

                let current_target = target_clone_builder.load(Ordering::Relaxed);
                let current_max = max_size_builder.load(Ordering::Relaxed);
                let current_idle = q_clone_builder.lock().await.len();
                let current_in_flight = in_flight_clone.load(Ordering::Relaxed);

                // 判断是否需要补充连接：闲置 + 正在建连的 < 目标，且没有触碰动态上限
                if current_idle + current_in_flight >= current_target
                    || current_idle + current_in_flight >= current_max
                {
                    // 等待消费者拿走连接，或者Manager提升目标值
                    tokio::time::sleep(Duration::from_millis(50)).await;
                    continue;
                }

                // 失败自适应退避: 若近期发生网络中断或握手连续失败，主循环主动退避，防止高频空转耗尽 FD 与 CPU
                let failures = stats_builder
                    .read()
                    .unwrap_or_else(|e| e.into_inner())
                    .consecutive_failures;
                if failures > 0 {
                    let backoff = builder_backoff(failures);
                    tokio::time::sleep(backoff).await;
                }

                // 突发并发补货 (Burst Refill):
                // consecutive_failures > 0 时 burst 恒为 1 (不再并发补多条);
                // 成功后恢复原 burst 逻辑：若池子处于饥饿状态 (current_idle == 0)，立即取消平稳期阶梯等待，一次性并发补充多条连接；
                // 若池子处于平稳补货期 (current_idle > 0)，施加 150ms 阶梯延迟平滑 SYN 抖动。
                let burst_count = if failures > 0 {
                    1
                } else if current_idle == 0 {
                    let needed = current_target.saturating_sub(current_idle + current_in_flight);
                    needed.clamp(1, 8)
                } else {
                    let now = Instant::now();
                    if next_build_at > now {
                        tokio::time::sleep_until(next_build_at).await;
                    }
                    next_build_at =
                        Instant::now() + Duration::from_millis(150 + fastrand::u64(0..=150));
                    1
                };

                for _ in 0..burst_count {
                    let cfg_task = cfg_clone.clone();
                    let q_task = q_clone_builder.clone();
                    let n_task = n_clone_builder.clone();
                    let in_flight_task = in_flight_clone.clone();
                    let stats_task = stats_builder.clone();
                    let brutal_state_builder = brutal_state_builder.clone();
                    let shutdown_task = shutdown_builder.clone();

                    in_flight_clone.fetch_add(1, Ordering::Relaxed);

                    tokio::spawn(async move {
                        if shutdown_task.load(Ordering::Relaxed) {
                            in_flight_task.fetch_sub(1, Ordering::Relaxed);
                            return;
                        }
                        let start = Instant::now();
                        match Self::connect_upstream(&cfg_task, &brutal_state_builder).await {
                            Ok(tunnel) => {
                                let elapsed = start.elapsed().as_millis() as u64;
                                let unpaused = stats_task
                                    .write()
                                    .unwrap_or_else(|e| e.into_inner())
                                    .record_latency(elapsed);
                                if unpaused {
                                    tracing::info!(
                                        "WarmPool: 建连成功, 恢复后台预热并重置认证失败计数"
                                    );
                                }

                                if shutdown_task.load(Ordering::Relaxed) {
                                    in_flight_task.fetch_sub(1, Ordering::Relaxed);
                                    return;
                                }
                                q_task.lock().await.push_back(tunnel);
                                n_task.notify_one();
                                in_flight_task.fetch_sub(1, Ordering::Relaxed);
                                tracing::trace!("WarmPool: 预热连接就绪 ({}ms)", elapsed);
                            }
                            Err(e) => {
                                let is_auth = e.downcast_ref::<AuthFailure>().is_some();
                                let just_paused = stats_task
                                    .write()
                                    .unwrap_or_else(|e| e.into_inner())
                                    .record_failure_typed(is_auth);
                                if just_paused {
                                    tracing::warn!(
                                        "WarmPool: 连续认证失败达到 5 次, 暂停后台预热 5 分钟 (等待 purge_idle 或超时恢复)"
                                    );
                                }
                                in_flight_task.fetch_sub(1, Ordering::Relaxed);
                                error!("WarmPool: 上游连接失败: {:?}", e);
                            }
                        }
                    });
                }
            }
        });

        pool
    }

    /// 安全终止连接池后台协程并清空所有未使用的预热连接 (防 FD 泄漏)
    pub fn shutdown(&self) {
        if self.shutdown.swap(true, Ordering::SeqCst) {
            return;
        }
        if let Ok(mut q) = self.queue.try_lock() {
            let n = q.len();
            q.clear();
            tracing::info!("WarmPool: 已安全终止后台建连并清空 {n} 条预热连接");
        }
        self.notify.notify_waiters();
    }

    /// 移动端网络切换或亮屏唤醒时，立即清空空闲队列中的所有旧连接，
    /// 避免向已失效网卡/NAT超时的坏死 Socket 发包导致 RST 崩溃。
    /// 零 Runtime 异步上下文依赖，可在任何 JNI / Binder 系统回调线程安全调用。
    pub fn purge_idle(&self) {
        if let Ok(mut stats) = self.stats.write() {
            if stats.purge_auth_pause() {
                tracing::info!("WarmPool: purge_idle 触发, 恢复后台预热并重置认证失败计数");
            }
        }
        if let Ok(mut q) = self.queue.try_lock() {
            let n = q.len();
            q.clear();
            self.notify.notify_waiters();
            tracing::info!("WarmPool: 已冲刷 {n} 条空闲预热连接 (网络切换/自愈)");
        }
    }

    /// 查询当前是否处于认证失败暂停状态
    pub fn is_auth_paused(&self) -> bool {
        let mut stats = self.stats.write().unwrap_or_else(|e| e.into_inner());
        let (paused, expired) = stats.check_auth_pause(Instant::now());
        if expired {
            tracing::info!("WarmPool: 连续认证失败暂停已满 5 分钟, 自动恢复后台预热");
        }
        paused
    }

    /// 核心握手逻辑：建立 TCP 并包装 AEAD Crypto 层
    async fn connect_upstream(cfg: &PoolConfig, brutal_state: &BrutalState) -> Result<Tunnel> {
        // 建连 + 伪装握手 + 派生密钥: 默认物理 TCP; 配了 underlying 则经该出站拨号 (Mirage-over-X)。
        let (mut crypto_reader, mut crypto_writer) = match &cfg.underlying {
            Some(u) => Self::handshake_over_underlying(cfg, u).await?,
            None => Self::handshake_over_tcp(cfg, brutal_state).await?,
        };

        // 5. v0.4+ 协议: 收 server 主动下发的 TIME_SYNC 帧, 写入全局 TIME_OFFSET.
        //    帧格式: [0x01 type][0x01/0x02 ver][8B u64 BE server unix sec] = 10 字节
        //    v0.15 改为 fail-closed: v0.15 服务端恒发 TIME_SYNC, 超时 / 解密失败 / 非预期帧
        //    均直接返回错误放弃该连接。杜绝迟到的 TIME_SYNC 帧污染上层数据、认证失败连接
        //    (被转伪装站) 误入连接池等问题。
        // proto_ver 0x02 = 服务端开了 cipher agility, 需在下方协商。
        let server_agility = match tokio::time::timeout(
            std::time::Duration::from_secs(3),
            crypto_reader.recv_data(),
        )
        .await
        {
            Ok(Ok(data)) => process_time_sync_frame(&data)?,
            Ok(Err(e)) => {
                // 解密失败 = 服务端很可能拒了本次认证、把连接转发到了伪装站, 我们却在用
                // 密码派生的会话密钥去解伪装站的 TLS 流量 → 解不开。这是"认证没过"的信号。
                // 池子每次补货都会撞到, 故只详细提示一次 (避免刷屏)。
                static HINTED: std::sync::atomic::AtomicBool =
                    std::sync::atomic::AtomicBool::new(false);
                if !HINTED.swap(true, std::sync::atomic::Ordering::Relaxed) {
                    // 统一诊断文案 (与服务端 control.rs 共用, 见 hello_auth::session_decrypt_failure_hint)。
                    tracing::warn!(
                        "隧道认证疑似失败 (TIME_SYNC 解密失败: {:?})。{}",
                        e,
                        crate::crypto::hello_auth::session_decrypt_failure_hint()
                    );
                } else {
                    tracing::debug!("TIME_SYNC: recv failed: {:?}", e);
                }
                return Err(AuthFailure(format!("TIME_SYNC 接收/解密失败: {:?}", e)).into());
            }
            Err(_) => {
                tracing::warn!("TIME_SYNC: 等待服务端时间帧超时 (3s), 放弃建连 (fail-closed)");
                return Err(AuthFailure("TIME_SYNC 等待超时 (3s)".to_string()).into());
            }
        };

        // 6. cipher agility 协商 (仅服务端广播 0x02 时): 发 CIPHER_NEGO(本机AES), 读 CIPHER_ACK,
        //    两端 rekey 到协商 cipher。协商在加密 ChaCha20 信道内完成, ClientHello 未动 (指纹不变)。
        //    v0.15 fail-closed: 已发送 CIPHER_NEGO 后, CIPHER_ACK 超时 / 格式异常 / recv 错误
        //    均直接报错断开, 避免两端密钥或密码套件状态不同步导致死隧道入池。
        if server_agility {
            let nego = crate::crypto::cipher::build_cipher_nego(
                crate::crypto::cipher::local_supports_aes(),
            );
            crypto_writer
                .send_data(&nego)
                .await
                .map_err(|e| anyhow::anyhow!("cipher agility: 发送 CIPHER_NEGO 失败: {e}"))?;
            let ack = match tokio::time::timeout(
                std::time::Duration::from_secs(3),
                crypto_reader.recv_data(),
            )
            .await
            {
                Ok(Ok(ack)) => ack,
                Ok(Err(e)) => {
                    tracing::warn!("cipher agility: 接收 CIPHER_ACK 失败: {e}");
                    anyhow::bail!("cipher agility: 接收 CIPHER_ACK 失败: {e}");
                }
                Err(_) => {
                    tracing::warn!("cipher agility: 等待 CIPHER_ACK 超时 (3s)");
                    anyhow::bail!("cipher agility: 等待 CIPHER_ACK 超时 (3s)");
                }
            };
            if let Some(final_cipher) = crate::crypto::cipher::parse_cipher_ack(&ack) {
                if final_cipher != crypto_writer.cipher() {
                    crypto_writer.rekey(final_cipher);
                    crypto_reader.rekey(final_cipher);
                }
                tracing::debug!("cipher agility 协商为 {:?}", final_cipher);
            } else {
                tracing::warn!("cipher agility: CIPHER_ACK 格式异常");
                anyhow::bail!("cipher agility: CIPHER_ACK 格式异常");
            }
        }

        Ok(Tunnel::new(crypto_reader, crypto_writer))
    }

    /// 物理 TCP 建连 + 伪装握手 + 派生密钥 (默认路径; 带 brutal/nodelay/裸 fd 快路径, Tcp 变体)。
    ///
    /// 手动建 socket 并 **connect 之前** 调用 protect hook (Android VPNService 必须
    /// protect 隧道 socket, 否则 SYN 走 0.0.0.0/0→tun0 路由形成环路: 隧道连接触发新
    /// 隧道连接, 无限递归, 全部连接失败)。
    async fn handshake_over_tcp(
        cfg: &PoolConfig,
        brutal_state: &BrutalState,
    ) -> Result<(CryptoReader<TunnelRead>, CryptoWriter<TunnelWrite>)> {
        let addr = crate::net_util::join_host_port(&cfg.server_host, cfg.server_port);
        use std::net::SocketAddr;
        use std::os::unix::io::AsRawFd;

        // 解析服务器地址 (v4 优先, 与上游 connect_smart 一致的策略)。
        let mut addrs: Vec<SocketAddr> = tokio::net::lookup_host(&addr).await?.collect();
        addrs.sort_by_key(|a| if a.is_ipv4() { 0 } else { 1 });

        let mut last_err: Option<anyhow::Error> = None;
        let mut stream: Option<tokio::net::TcpStream> = None;
        for a in addrs {
            let sock = match a {
                SocketAddr::V4(_) => tokio::net::TcpSocket::new_v4(),
                SocketAddr::V6(_) => tokio::net::TcpSocket::new_v6(),
            }?;
            let raw_fd = sock.as_raw_fd();
            // 启用 TCP KeepAlive 并显式指定移动蜂窝网 NAT 活跃参数 (15s 探测, 5s 间隔, 3次重试, 击穿 30s CGNAT 阈值)
            let _ = sock.set_keepalive(true);
            #[cfg(unix)]
            unsafe {
                let idle: libc::c_int = 15;
                libc::setsockopt(
                    raw_fd,
                    libc::IPPROTO_TCP,
                    libc::TCP_KEEPIDLE,
                    &idle as *const _ as *const libc::c_void,
                    std::mem::size_of_val(&idle) as libc::socklen_t,
                );
                let intvl: libc::c_int = 5;
                libc::setsockopt(
                    raw_fd,
                    libc::IPPROTO_TCP,
                    libc::TCP_KEEPINTVL,
                    &intvl as *const _ as *const libc::c_void,
                    std::mem::size_of_val(&intvl) as libc::socklen_t,
                );
                let cnt: libc::c_int = 3;
                libc::setsockopt(
                    raw_fd,
                    libc::IPPROTO_TCP,
                    libc::TCP_KEEPCNT,
                    &cnt as *const _ as *const libc::c_void,
                    std::mem::size_of_val(&cnt) as libc::socklen_t,
                );
                // TCP MSS Clamping: 限制隧道 Socket 的 MSS，杜绝蜂窝网络 PMTU 黑洞
                let mss: libc::c_int = if a.is_ipv4() { 1360 } else { 1340 };
                libc::setsockopt(
                    raw_fd,
                    libc::IPPROTO_TCP,
                    libc::TCP_MAXSEG,
                    &mss as *const _ as *const libc::c_void,
                    std::mem::size_of_val(&mss) as libc::socklen_t,
                );
            }
            // ⚠️ protect 必须在 connect 之前 (SO_MARK 影响路由选择)
            crate::protect::protect(raw_fd);
            // Brutal 拥塞控制 (仅 config 配了 brutal_rate_mbps 时): 直接对裸 fd 设置。
            if brutal_state.configured_rate.is_some() {
                let current_rate = brutal_state
                    .current_rate
                    .load(std::sync::atomic::Ordering::Relaxed);
                crate::proxy::brutal::apply_brutal(sock.as_raw_fd(), current_rate);
            }
            // 8s 超时: 黑洞路由 (丢 SYN 不回 RST) 下 connect 会挂到内核 tcp_syn_retries (~127s)。
            match timeout(Duration::from_secs(8), sock.connect(a)).await {
                Ok(Ok(s)) => {
                    stream = Some(s);
                    break;
                }
                Ok(Err(e)) => {
                    last_err = Some(anyhow::anyhow!("connect {addr}: {e}"));
                    continue;
                }
                Err(_) => {
                    last_err = Some(anyhow::anyhow!(
                        "connect to {addr} timed out (8s, 黑洞路由?)"
                    ));
                    continue;
                }
            }
        }
        let stream = stream.ok_or_else(|| {
            last_err.unwrap_or_else(|| anyhow::anyhow!("connect to {addr} failed"))
        })?;
        stream.set_nodelay(true)?; // 关 Nagle, 降首包延迟

        let (mut read_half, mut write_half) = stream.into_split();
        let hs = Self::do_fake_tls(&mut read_half, &mut write_half, cfg).await?;
        // 恒 Tcp 变体 (保留 try_read 探活 + 裸 fd 调 brutal)。PFS 开时走 ecdh 派生。
        Ok(match hs.ecdh {
            Some(ecdh) => create_crypto_pair_pfs(
                TunnelRead::Tcp(read_half),
                TunnelWrite::Tcp(write_half),
                &cfg.password,
                &hs.client_random,
                &hs.server_random,
                &ecdh,
                true,
            ),
            None => create_crypto_pair(
                TunnelRead::Tcp(read_half),
                TunnelWrite::Tcp(write_half),
                &cfg.password,
                &hs.client_random,
                &hs.server_random,
                true,
            ),
        })
    }

    /// 经 underlying 出站拨号 server:port (Mirage-over-X 链式): 无裸 fd → 无 brutal/nodelay,
    /// 底层拥塞控制由 underlying 出站负责。返回 Boxed 变体隧道。
    async fn handshake_over_underlying(
        cfg: &PoolConfig,
        underlying: &Arc<OutboundNode>,
    ) -> Result<(CryptoReader<TunnelRead>, CryptoWriter<TunnelWrite>)> {
        let target = Address::Domain(cfg.server_host.clone(), cfg.server_port);
        let out = timeout(Duration::from_secs(15), underlying.connect(&target))
            .await
            .map_err(|_| {
                anyhow::anyhow!(
                    "经 underlying 连 {}:{} 超时 (15s)",
                    cfg.server_host,
                    cfg.server_port
                )
            })??;
        let (mut read_half, mut write_half) = tokio::io::split(out);
        let hs = Self::do_fake_tls(&mut read_half, &mut write_half, cfg).await?;
        Ok(match hs.ecdh {
            Some(ecdh) => create_crypto_pair_pfs(
                TunnelRead::Boxed(Box::new(read_half)),
                TunnelWrite::Boxed(Box::new(write_half)),
                &cfg.password,
                &hs.client_random,
                &hs.server_random,
                &ecdh,
                true,
            ),
            None => create_crypto_pair(
                TunnelRead::Boxed(Box::new(read_half)),
                TunnelWrite::Boxed(Box::new(write_half)),
                &cfg.password,
                &hs.client_random,
                &hs.server_random,
                true,
            ),
        })
    }

    /// 伪装 TLS 握手 (发带 token 的 ClientHello / 读 server flight / 发假 Finished tail)。
    /// 返回 client_random (会话密钥派生的 salt) + 可选 ecdh (PFS 开时)。
    /// 对任意字节流生效 (物理 TCP / underlying 流)。
    async fn do_fake_tls<Rd, Wr>(
        rh: &mut Rd,
        wh: &mut Wr,
        cfg: &PoolConfig,
    ) -> Result<ClientHandshake>
    where
        Rd: tokio::io::AsyncRead + Unpin,
        Wr: tokio::io::AsyncWrite + Unpin,
    {
        // v0.15 协议新鲜性: 先定 ClientHello.random (PFS 时 = 客户端临时公钥的 Elligator2 表示),
        // 再以它为 bind 生成 token (与上游 mirage-rs pool::do_fake_tls 一致)。
        let (ephemeral, client_random) = if cfg.pfs {
            let e = crate::crypto::pfs::Ephemeral::generate()?;
            let pk = e.public;
            (Some(e), pk)
        } else {
            let mut r = [0u8; 32];
            rand::fill(&mut r);
            (None, r)
        };
        let token = crate::crypto::hello_auth::make_session_token(&cfg.password, &client_random);
        let hello_bytes = crate::crypto::tls_raw::build_client_hello_with_random(
            &cfg.camouflage_host,
            &token,
            &client_random,
        );
        wh.write_all(&hello_bytes).await?;
        wh.flush().await?;
        // read_server_handshake 在 server_random 全 0 时已 fail-closed 报错。
        let handshake = read_server_handshake(rh).await?;
        // 伪造 Client Finished 长度随协商套件 (0x1302 → 69B 体, 其余 53B), 与服务端结构化校验一致。
        let tail_bytes = crate::crypto::tls_raw::build_fake_client_tail(handshake.cipher_suite);
        wh.write_all(&tail_bytes).await?;
        wh.flush().await?;
        let server_random = handshake.server_random;
        // PFS: 与服务端临时公钥 (= server_random 的 Elligator2 表示) 做 ECDH 得共享秘密。
        let ecdh = match ephemeral {
            Some(e) => Some(e.agree(&server_random)?),
            None => None,
        };
        Ok(ClientHandshake {
            client_random,
            server_random,
            ecdh,
        })
    }

    /// O(1) 复杂度提取连接.
    ///
    /// 如果队列有现成连接, 0 延迟返回. 队列空时挂起等待 `notify` 唤醒.
    ///
    /// ★ 10s 超时硬上限 (修 bug: 雪崩) — 之前签名是 `-> Tunnel` infallible,
    /// builder 上游死后只 log + sleep 重试, 永不调 notify_one. 每个 pool.get()
    /// 死等, 浏览器请求堆积成百上千 → FD 耗尽 OOM. 现在返回 `Result<Tunnel>`,
    /// 10s 还拿不到就报错让调用方放弃这次请求, 不堆积.
    ///
    /// 反馈式弹性 (v0.4.2+) 仪表化: 入口记录开始时间, 拿到 tunnel 后若总耗时
    /// \> 50ms 计一次 wait_event. Manager task 用此比率决定下周期 target 调整.
    /// 从空闲队列中弹出一个未过期且健康的隧道 (0 延迟)。
    async fn pop_valid_tunnel(&self) -> Option<Tunnel> {
        let mut q = self.queue.lock().await;
        while let Some(tunnel) = q.pop_front() {
            if tunnel.created_at.elapsed().as_secs() > tunnel.max_age_sec {
                tracing::debug!(
                    "Tunnel reached max age ({}s), gracefully closing",
                    tunnel.created_at.elapsed().as_secs()
                );
                tokio::spawn(async move {
                    let mut t = tunnel;
                    let _ = t.writer.send_close_notify().await;
                });
                continue;
            }
            if tunnel.is_stale() {
                tracing::debug!("WarmPool: discarded stale tunnel (FIN/RST before dispatch)");
                tokio::spawn(async move {
                    let mut t = tunnel;
                    let _ = t.writer.send_close_notify().await;
                });
                continue;
            }
            return Some(tunnel);
        }
        None
    }

    /// 阻塞等待空闲队列就绪。
    async fn wait_for_queue(&self) -> Result<Tunnel> {
        loop {
            let notified = self.notify.notified();
            if let Some(tunnel) = self.pop_valid_tunnel().await {
                return Ok(tunnel);
            }
            notified.await;
        }
    }

    /// O(1) 复杂度提取连接.
    ///
    /// 1. 队列有现成连接时 0 延迟直接返回。
    /// 2. 突发高并发 (例如社交 App 同时加载 10-20 张图片) 导致预热池瞬间耗尽时:
    ///    立即发起并行的即时拨号 (On-Demand Dial)，同时与后台补货队列竞争 (Select Race)！
    ///    彻底消除单协程阶梯排队导致的"图片逐个断断续续加载"卡顿。
    pub async fn get(&self) -> Result<Tunnel> {
        self.metrics.total_gets.fetch_add(1, Ordering::Relaxed);
        let wait_start = Instant::now();

        // 路径 1: 0ms 直接命中预热就绪隧道
        if let Some(tunnel) = self.pop_valid_tunnel().await {
            return Ok(tunnel);
        }

        // 路径 2: 预热池耗尽 → 即时并行拨号 (信号量限流) + 与后台补货竞争
        //
        // 并发拨号限流: 用信号量把瞬时同时进行的 On-Demand 握手限制在
        // clamp(pool_size, 4, 16) 条内, 避免 20 张图片瞬间喷 20 条 TLS 握手
        // (thundering herd: 客户端 CPU/带宽冲击 + 服务端 accept 压力)。
        // 信号量满(已有 16 条在拨号)时, 本条请求不再新起拨号, 只等后台补货。
        let cfg = self.cfg.clone();
        let brutal = self.brutal_state.clone();
        let stats = self.stats.clone();
        let permit = self.on_demand_sem.clone().try_acquire_owned().ok();

        let result = tokio::time::timeout(Duration::from_secs(10), async {
            let Some(permit) = permit else {
                // 并发拨号已满: 不新起 on-demand, 等后台补货队列
                return self.wait_for_queue().await;
            };

            let on_demand = async move {
                // permit 在此持有: block 结束 drop → 信号量释放 (允许下一条 on-demand)
                let _permit = permit;
                let start = Instant::now();
                let res = Box::pin(Self::connect_upstream(&cfg, &brutal)).await;
                if let Ok(ref _t) = res {
                    let elapsed = start.elapsed().as_millis() as u64;
                    let unpaused = stats
                        .write()
                        .unwrap_or_else(|e| e.into_inner())
                        .record_latency(elapsed);
                    if unpaused {
                        tracing::info!("WarmPool: on-demand 建连成功, 解除认证暂停并重置失败计数");
                    }
                    debug!("WarmPool: On-Demand 即时建连就绪 ({}ms)", elapsed);
                }
                res
            };

            tokio::select! {
                res_demand = on_demand => {
                    match res_demand {
                        Ok(tunnel) => {
                            if wait_start.elapsed() > Duration::from_millis(50) {
                                self.metrics.wait_events.fetch_add(1, Ordering::Relaxed);
                            }
                            // 回流策略: 队列未达目标水位时, on-demand 连接先沉淀进池
                            // (后续请求复用, 平滑对外流量形态), 再从池取一条返回
                            self.refill_or_take(tunnel).await
                        }
                        Err(e) => {
                            debug!("WarmPool: On-demand 建连失败, 回落等待池: {e}");
                            self.wait_for_queue().await
                        }
                    }
                }
                res_pool = self.wait_for_queue() => {
                    if wait_start.elapsed() > Duration::from_millis(50) {
                        self.metrics.wait_events.fetch_add(1, Ordering::Relaxed);
                    }
                    res_pool
                }
            }
        })
        .await;

        match result {
            Ok(Ok(tunnel)) => Ok(tunnel),
            Ok(Err(e)) => {
                self.metrics.wait_events.fetch_add(1, Ordering::Relaxed);
                Err(e)
            }
            Err(_) => {
                self.metrics.wait_events.fetch_add(1, Ordering::Relaxed);
                anyhow::bail!("pool.get() timed out after 10s — upstream likely unreachable")
            }
        }
    }

    /// On-Demand 建连成功后的回流策略:
    /// - 队列未达目标水位 → 连接沉淀进池 (后续请求复用, 避免每波突发都全新建握手),
    ///   再从池取一条**健康**连接返回 (pop_valid_tunnel 跳过 stale);
    /// - 池已满 → 直接使用本连接。
    async fn refill_or_take(&self, tunnel: Tunnel) -> Result<Tunnel> {
        let should_refill = {
            let q = self.queue.lock().await;
            q.len() < self.target_size.load(Ordering::Relaxed)
        };
        if should_refill {
            {
                let mut q = self.queue.lock().await;
                q.push_back(tunnel);
            }
            // ⚠️ 回流必须 notify: 否则 wait_for_queue 里挂起的请求不被唤醒,
            //    池子补充了也拿不到 (实测 20 请求渐进到 8s 的根因之一)
            self.notify.notify_one();
            // 从池取一条健康连接 (可能取到刚回流的新连接, 也可能取到后台补货的;
            // 刚回流的是健康的, 因此池里至少一条可用)
            if let Some(t) = self.pop_valid_tunnel().await {
                return Ok(t);
            }
            // pop_valid_tunnel 只在队列全 stale 时返回 None —— 刚回流一条健康连接,
            // 理论不可能; 兜底退化为直接拨号等待 (极罕见)
            return self.wait_for_queue().await;
        }
        Ok(tunnel)
    }

    pub async fn update_brutal_rate(&self, new_rate: u64) {
        self.brutal_state
            .current_rate
            .store(new_rate, std::sync::atomic::Ordering::Relaxed);

        // ⚠️ 修 F2 (fd 复用竞态): 旧实现先把裸 fd 收集进 Vec、出锁后再 spawn_blocking
        // setsockopt。快照与 syscall 之间, idle tunnel 可能被 get() 弹出并 Drop(关 fd)、
        // 或 active tunnel 的 ActiveFdGuard Drop 移除 → fd 号被内核回收给**另一条**连接,
        // setsockopt 把 brutal CC 误套到无关 socket 上。
        //
        // 修法: 持锁期间直接 setsockopt。①持 queue 锁时 idle tunnel 无法被 get() 弹出关闭;
        // ②持 active_fds 锁时 ActiveFdGuard::drop (同锁 remove) 被阻塞 → handler 卡在
        // drop(_guard) 无法继续 drop tunnel → fd 保持存活。setsockopt 是 µs 级非阻塞
        // syscall, update_brutal_rate 仅 RTT 反馈循环 >5% 变化时触发 (秒级, 且 brutal
        // 默认关), 持锁批量执行开销可忽略, 无跨 .await 持锁。
        let mut total = 0usize;
        {
            let q = self.queue.lock().await;
            for t in q.iter() {
                // 物理 TCP 隧道 → Some(fd) 调 brutal。嵌套 (Mirage-over-X, Boxed) 隧道也在池里但
                // 无裸 fd → None → 跳过 brutal (其拥塞控制由 underlying 出站的物理层负责)。
                if let Some(fd) = t.get_raw_fd() {
                    crate::proxy::brutal::set_brutal_rate(fd, new_rate, 0); // 客户端出站不分组
                }
                total += 1;
            }
        }
        if let Ok(actives) = self.brutal_state.active_fds.lock() {
            for &fd in actives.iter() {
                crate::proxy::brutal::set_brutal_rate(fd, new_rate, 0); // 客户端出站不分组
                total += 1;
            }
        }
        tracing::debug!(
            "Updated Brutal rate to {} bps for {} tunnels (idle + active)",
            new_rate,
            total
        );
    }

    pub fn active_fd_guard(&self, fd: i32) -> ActiveFdGuard {
        if let Ok(mut lock) = self.brutal_state.active_fds.lock() {
            lock.insert(fd);
        }
        ActiveFdGuard {
            state: self.brutal_state.clone(),
            fd,
        }
    }

    /// 动态热更新连接池目标与最大容量 (零停机、无需重建底层引擎与销毁已有 Fake-IP/连接)
    pub fn set_pool_size(&self, new_size: usize) {
        let size = new_size.max(1);
        let old_max = self.max_size.swap(size, Ordering::Relaxed);
        let cur = self.target_size.load(Ordering::Relaxed);
        let new_target = if size > old_max {
            // 容量上限上调（如亮屏/突发高并发）：迅速抬升初始水位至至少 size / 2，缩短冷启动爬坡延迟
            cur.max(size / 2).min(size).max(1)
        } else {
            // 容量上限下调（如息屏低功耗模式）：平滑收敛至新上限
            cur.min(size).max(1)
        };
        self.target_size.store(new_target, Ordering::Relaxed);
        self.notify.notify_waiters();
        tracing::info!("[WarmPool] 动态调整连接池容量上限: {size} (原上限: {old_max}, 当前目标水位: {new_target})");
    }

    pub fn get_pool_size(&self) -> usize {
        self.max_size.load(Ordering::Relaxed)
    }
}

impl Drop for WarmPool {
    fn drop(&mut self) {
        self.shutdown();
    }
}
