//! 自适应与在线学习连接分类引擎 (Adaptive Traffic Classifier & Profile Learner)。
//!
//! 针对移动端多样化网络流量（图片/媒体 CDN、IM 实时推送/长轮询、SSH/交互式终端、通用 Web/API）：
//! 1. **静态特征分类 (Static Match)**：
//!    - 端口 22/23/3389 -> Interactive (300s)
//!    - 已知图片/媒体 CDN 域名后缀 -> MediaCdn (传输后 10s 快速回收)
//!    - 已知 IM/实时推送服务域名后缀 -> PushIm (180s 保活)
//! 2. **动态行为自学习 (Dynamic Online Learning)**：
//!    - 记录每个域名的历史会话样本：总连接数、平均上行/下行流量、下行/上行比率 (ratio)、会话持续时间。
//!    - 若观察到高频下行（如 ratio >= 6.0 且 单连接下行 >= 16KB），自学习归类为 `MediaCdn` (收紧超时至 10s，加速池位释放)。
//!    - 若观察到持续双向小包（如 avg_up <= 2KB 且 avg_down <= 4KB 且 连接时间较长），自学习归类为 `PushIm` (放宽超时至 180s，避免误杀推送)。
//! 3. **闭环强化调优 (Closed-Loop Feedback & Reinforcement - Phase 2)**：
//!    - **重连反弹惩罚 (Churn Penalty)**: 若连接刚因 IdleTimeout 关闭在 4s 内同一域名立即重建 SYN，判定为过早掐断，自动延长 1.5 倍超时 (上限 300s)。
//!    - **僵尸空闲衰减 (Zombie Decay)**: 若连接空闲超时退出且全程未被复用，判定超时偏长，自动收紧 20% (下限 5s/10s)。
//!    - **复用命中奖励 (Reuse Hit)**: 记录复用次数，稳定当前高效时延。
//! 4. **原子持久化 (Crash-Safe Disk Persistence - Phase 3)**：
//!    - 支持原子保存/恢复用户专属历史画像字典。

use std::collections::HashMap;
use std::sync::RwLock;
use std::time::Duration;
use serde::{Deserialize, Serialize};
use tracing::debug;

/// 流量分类枚举
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TrafficCategory {
    /// 交互终端 (SSH/Telnet/RDP) -> 300s
    Interactive,
    /// 图片/音视频 CDN (短空闲快速回收) -> 10s (首包 15s)
    MediaCdn,
    /// IM 推送/实时长轮询 -> 180s
    PushIm,
    /// 通用 Web / REST API -> 30s (首包 15s)
    GeneralApi,
}

impl TrafficCategory {
    pub fn default_idle_secs(&self) -> u64 {
        match self {
            TrafficCategory::Interactive => 300,
            TrafficCategory::MediaCdn => 10,
            TrafficCategory::PushIm => 180,
            TrafficCategory::GeneralApi => 30,
        }
    }

    pub fn idle_timeout(&self, is_active_transfer: bool) -> Duration {
        match self {
            TrafficCategory::Interactive => Duration::from_secs(300),
            TrafficCategory::MediaCdn => {
                if is_active_transfer {
                    Duration::from_secs(10)
                } else {
                    Duration::from_secs(15)
                }
            }
            TrafficCategory::PushIm => Duration::from_secs(180),
            TrafficCategory::GeneralApi => {
                if is_active_transfer {
                    Duration::from_secs(30)
                } else {
                    Duration::from_secs(15)
                }
            }
        }
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            TrafficCategory::Interactive => "Interactive",
            TrafficCategory::MediaCdn => "MediaCDN",
            TrafficCategory::PushIm => "PushIM",
            TrafficCategory::GeneralApi => "GeneralAPI",
        }
    }
}

/// 连接关闭原因
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum CloseReason {
    ClientClosed,
    ServerClosed,
    IdleTimeout,
    Error,
}

/// 单个域名的学习画像与时延设定记录
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DomainProfile {
    pub domain: String,
    pub category: TrafficCategory,
    pub sample_count: u64,
    pub total_up_bytes: u64,
    pub total_down_bytes: u64,
    pub avg_down_up_ratio: f32,
    pub assigned_idle_secs: u64,
    pub is_static_rule: bool,
    pub last_seen_secs: u64,

    // --- Phase 2 闭环调优自适应统计 ---
    pub churn_penalties: u32,
    pub zombie_decays: u32,
    pub reuse_hits: u32,
    pub last_closed_secs: u64,
    pub last_close_reason: CloseReason,
}

fn unix_now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

/// 静态 CDN 域名后缀列表
const CDN_SUFFIXES: &[&str] = &[
    "twimg.com",
    "ytimg.com",
    "ggpht.com",
    "googleusercontent.com",
    "fbcdn.net",
    "cdninstagram.com",
    "akamaized.net",
    "cloudfront.net",
    "fastly.net",
    "tiktokcdn.com",
    "byteoversea.com",
    "vimeocdn.com",
    "edgesuite.net",
    "llnwd.net",
    "cdn.sstatic.net",
    "githubusercontent.com",
];

/// 静态 IM / 推送服务域名后缀列表
const IM_SUFFIXES: &[&str] = &[
    "telegram.org",
    "t.me",
    "whatsapp.net",
    "whatsapp.com",
    "mtalk.google.com",
    "push.apple.com",
    "discord.gg",
    "discord.com",
    "matrix.org",
    "signal.org",
];

#[inline]
pub fn is_interactive_port(port: u16) -> bool {
    matches!(port, 22 | 23 | 3389)
}

#[inline]
pub fn is_image_or_media_cdn(host: &str) -> bool {
    let h = host.trim_end_matches('.');
    CDN_SUFFIXES.iter().any(|&suffix| h == suffix || h.ends_with(&format!(".{suffix}")))
}

#[inline]
pub fn is_push_or_im_service(host: &str) -> bool {
    let h = host.trim_end_matches('.');
    IM_SUFFIXES.iter().any(|&suffix| h == suffix || h.ends_with(&format!(".{suffix}")))
}

/// 全局域名自学习画像存储器
static PROFILES_STORE: RwLock<Option<HashMap<String, DomainProfile>>> = RwLock::new(None);
const MAX_PROFILES_CAPACITY: usize = 512;

/// 初始化或获取存储引用
fn with_profiles_read<F, R>(f: F) -> R
where
    F: FnOnce(&HashMap<String, DomainProfile>) -> R,
{
    let read_guard = PROFILES_STORE.read().unwrap_or_else(|e| e.into_inner());
    if let Some(ref map) = *read_guard {
        return f(map);
    }
    drop(read_guard);
    let mut write_guard = PROFILES_STORE.write().unwrap_or_else(|e| e.into_inner());
    let map = write_guard.get_or_insert_with(HashMap::new);
    f(map)
}

fn with_profiles_write<F, R>(f: F) -> R
where
    F: FnOnce(&mut HashMap<String, DomainProfile>) -> R,
{
    let mut write_guard = PROFILES_STORE.write().unwrap_or_else(|e| e.into_inner());
    let map = write_guard.get_or_insert_with(HashMap::new);
    f(map)
}

/// 判定连接分类并返回推荐的空闲超时 (包含 Phase 2 重连反弹检测)
pub fn classify_connection(dst_port: u16, domain: Option<&str>) -> (TrafficCategory, Duration) {
    if is_interactive_port(dst_port) {
        return (TrafficCategory::Interactive, Duration::from_secs(300));
    }

    if let Some(dom) = domain {
        let dom_lower = dom.to_lowercase();
        let now = unix_now_secs();

        // 1. 查询画像并检查是否有重连反弹 (Churn / Ping-Pong Reconnect)
        let found = with_profiles_write(|map| {
            if let Some(profile) = map.get_mut(&dom_lower) {
                profile.last_seen_secs = now;
                // 重连反弹检测 (仅对动态 GeneralApi 规则生效, 静态规则与 CDN/IM 不参与 Churn 放大)
                if !profile.is_static_rule
                    && profile.category == TrafficCategory::GeneralApi
                    && profile.last_closed_secs > 0
                    && now.saturating_sub(profile.last_closed_secs) <= 4
                    && profile.last_close_reason == CloseReason::IdleTimeout
                {
                    let old_timeout = profile.assigned_idle_secs;
                    // 动态拉长 1.5 倍 (上限 120s)
                    profile.assigned_idle_secs = (profile.assigned_idle_secs * 15 / 10).min(120);
                    profile.churn_penalties += 1;
                    debug!(
                        "[AdaptiveProfile] 域名 [{}] 触发重连反弹惩罚 ({}s 前被超时掐断): {}s -> {}s (累计惩罚 {} 次)",
                        dom_lower,
                        now.saturating_sub(profile.last_closed_secs),
                        old_timeout,
                        profile.assigned_idle_secs,
                        profile.churn_penalties
                    );
                }
                return Some((profile.category, profile.assigned_idle_secs));
            }
            None
        });

        if let Some((cat, idle_secs)) = found {
            return (cat, Duration::from_secs(idle_secs));
        }

        // 2. 静态规则快速命中 (确定性超时, 标记为 is_static=true)
        if is_image_or_media_cdn(&dom_lower) {
            let cat = TrafficCategory::MediaCdn;
            let timeout = cat.idle_timeout(false);
            record_initial_profile(dom_lower, cat, true);
            return (cat, timeout);
        }

        if is_push_or_im_service(&dom_lower) {
            let cat = TrafficCategory::PushIm;
            let timeout = cat.idle_timeout(false);
            record_initial_profile(dom_lower, cat, true);
            return (cat, timeout);
        }
    }

    (TrafficCategory::GeneralApi, TrafficCategory::GeneralApi.idle_timeout(false))
}

fn record_initial_profile(domain: String, category: TrafficCategory, is_static: bool) {
    with_profiles_write(|map| {
        if map.len() >= MAX_PROFILES_CAPACITY && !map.contains_key(&domain) {
            // LRU: 清理最早未访问的一个条目
            if let Some(oldest_key) = map.iter().min_by_key(|(_, p)| p.last_seen_secs).map(|(k, _)| k.clone()) {
                map.remove(&oldest_key);
            }
        }
        map.entry(domain.clone()).or_insert_with(|| DomainProfile {
            domain,
            category,
            sample_count: 0,
            total_up_bytes: 0,
            total_down_bytes: 0,
            avg_down_up_ratio: 1.0,
            assigned_idle_secs: category.default_idle_secs(),
            is_static_rule: is_static,
            last_seen_secs: unix_now_secs(),
            churn_penalties: 0,
            zombie_decays: 0,
            reuse_hits: 0,
            last_closed_secs: 0,
            last_close_reason: CloseReason::ServerClosed,
        });
    });
}

/// 计算自适应超时时长
pub fn compute_adaptive_timeout(
    dst_port: u16,
    domain: Option<&str>,
    is_active_transfer: bool,
) -> Duration {
    if is_interactive_port(dst_port) {
        return Duration::from_secs(300);
    }
    // 未传输阶段: 统一给 15s 初始等待
    if !is_active_transfer {
        return Duration::from_secs(15);
    }
    if let Some(dom) = domain {
        let dom_lower = dom.to_lowercase();
        if let Some(profile) = with_profiles_read(|map| map.get(&dom_lower).cloned()) {
            return Duration::from_secs(profile.assigned_idle_secs);
        }
    }
    let (cat, _) = classify_connection(dst_port, domain);
    cat.idle_timeout(is_active_transfer)
}

/// 记录一次连接生命周期的吞吐指标并触发自适应学习画像与闭环调优
pub fn record_conn_metrics(
    domain: Option<&str>,
    up_bytes: u64,
    down_bytes: u64,
    duration_ms: u64,
    close_reason: CloseReason,
    is_reused: bool,
) {
    let Some(dom) = domain else { return };
    if dom.is_empty() { return; }
    let dom_lower = dom.to_lowercase();
    let now = unix_now_secs();

    with_profiles_write(|map| {
        if map.len() >= MAX_PROFILES_CAPACITY && !map.contains_key(&dom_lower) {
            // LRU 淘汰最久未见条目
            if let Some(oldest_key) = map.iter().min_by_key(|(_, p)| p.last_seen_secs).map(|(k, _)| k.clone()) {
                map.remove(&oldest_key);
            }
        }
        let profile = map.entry(dom_lower.clone()).or_insert_with(|| {
            let initial_cat = if is_image_or_media_cdn(&dom_lower) {
                TrafficCategory::MediaCdn
            } else if is_push_or_im_service(&dom_lower) {
                TrafficCategory::PushIm
            } else {
                TrafficCategory::GeneralApi
            };
            DomainProfile {
                domain: dom_lower.clone(),
                category: initial_cat,
                sample_count: 0,
                total_up_bytes: 0,
                total_down_bytes: 0,
                avg_down_up_ratio: 1.0,
                assigned_idle_secs: initial_cat.default_idle_secs(),
                is_static_rule: initial_cat != TrafficCategory::GeneralApi,
                last_seen_secs: now,
                churn_penalties: 0,
                zombie_decays: 0,
                reuse_hits: 0,
                last_closed_secs: now,
                last_close_reason: close_reason,
            }
        });

        profile.sample_count += 1;
        profile.total_up_bytes += up_bytes;
        profile.total_down_bytes += down_bytes;
        profile.last_seen_secs = now;
        profile.last_closed_secs = now;
        profile.last_close_reason = close_reason;

        let ratio = profile.total_down_bytes as f32 / (profile.total_up_bytes.max(1) as f32);
        profile.avg_down_up_ratio = ratio;

        if is_reused {
            profile.reuse_hits += 1;
        }

        // --- Phase 2: 闭环强化逻辑 (仅对非静态规则的 GeneralApi 生效) ---
        // 静态规则 (MediaCdn/PushIm/Interactive) 超时绝对锁定，不参与衰减
        if !profile.is_static_rule && profile.category == TrafficCategory::GeneralApi {
            if close_reason == CloseReason::IdleTimeout && !is_reused {
                // 僵尸空闲衰减: 达到超时但全程无多请求复用，向下收敛 20% (下限 10s)
                let old_timeout = profile.assigned_idle_secs;
                profile.assigned_idle_secs = (profile.assigned_idle_secs * 8 / 10).max(10);
                profile.zombie_decays += 1;
                debug!(
                    "[AdaptiveProfile] 域名 [{}] 触发僵尸空闲衰减 (超时未复用): {}s -> {}s (累计衰减 {} 次)",
                    dom_lower, old_timeout, profile.assigned_idle_secs, profile.zombie_decays
                );
            }
        }

        // 如果不是静态锁定的规则且样本数 >= 2，进行基础分类自适应演进
        if !profile.is_static_rule && profile.sample_count >= 2 {
            let avg_down = profile.total_down_bytes / profile.sample_count;
            let avg_up = profile.total_up_bytes / profile.sample_count;

            let prev_cat = profile.category;

            // 特征 1: 高下载/上行比且单次吞吐较大 -> 自动演进为 MediaCdn (10s 快速回收)
            if ratio >= 6.0 && avg_down >= 16 * 1024 {
                profile.category = TrafficCategory::MediaCdn;
                profile.assigned_idle_secs = 10;
                // 重置动态惩罚/衰减计数，恢复标准 CDN 短保活策略
                profile.churn_penalties = 0;
                profile.zombie_decays = 0;
            }
            // 特征 2: 双向持续微量小包且连接持续时间较长 -> 自动演进为 PushIm (180s 长保活)
            else if avg_up <= 2048 && avg_down <= 4096 && duration_ms >= 8000 {
                profile.category = TrafficCategory::PushIm;
                profile.assigned_idle_secs = 180;
                profile.churn_penalties = 0;
                profile.zombie_decays = 0;
            }

            if prev_cat != profile.category {
                debug!(
                    "[AdaptiveProfile] 域名 [{}] 学习演进: {:?} -> {:?} (采样: {}, ↓/↑: {:.1}, 设定超时: {}s)",
                    dom_lower, prev_cat, profile.category, profile.sample_count, ratio, profile.assigned_idle_secs
                );
            }
        }
    });
}

/// 导出所有已学得的域名流量画像记录 (JSON 格式)
pub fn get_learned_profiles_json() -> String {
    with_profiles_read(|map| {
        let mut list: Vec<&DomainProfile> = map.values().collect();
        // 按采样次数由大到小排序
        list.sort_by(|a, b| b.sample_count.cmp(&a.sample_count));
        serde_json::to_string(&list).unwrap_or_else(|_| "[]".to_string())
    })
}

/// 持久化已学得的画像到磁盘文件 (Phase 3 原子写入: 写入 .tmp 再 rename 避免崩溃损毁)
pub fn save_profiles_to_disk(file_path: &str) -> std::io::Result<()> {
    let json_data = get_learned_profiles_json();
    let tmp_path = format!("{file_path}.tmp");
    std::fs::write(&tmp_path, json_data.as_bytes())?;
    std::fs::rename(&tmp_path, file_path)?;
    debug!("[AdaptiveProfile] 成功原子持久化流量画像到 {}", file_path);
    Ok(())
}

/// 从磁盘文件恢复加载历史画像 (Phase 3)
pub fn load_profiles_from_disk(file_path: &str) -> std::io::Result<usize> {
    if !std::path::Path::new(file_path).exists() {
        return Ok(0);
    }
    let data = std::fs::read_to_string(file_path)?;
    let profiles: Vec<DomainProfile> = serde_json::from_str(&data)
        .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e))?;
    let count = profiles.len();
    with_profiles_write(|map| {
        for p in profiles {
            map.insert(p.domain.clone(), p);
        }
    });
    debug!("[AdaptiveProfile] 成功从 {} 恢复加载 {} 条历史流量画像", file_path, count);
    Ok(count)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_static_classification() {
        assert_eq!(classify_connection(22, None).0, TrafficCategory::Interactive);
        assert_eq!(classify_connection(443, Some("pbs.twimg.com")).0, TrafficCategory::MediaCdn);
        assert_eq!(classify_connection(443, Some("mtalk.google.com")).0, TrafficCategory::PushIm);
        assert_eq!(classify_connection(443, Some("api.unknown-service.org")).0, TrafficCategory::GeneralApi);
    }

    #[test]
    fn test_dynamic_learning_to_media_cdn() {
        let domain = "test-gallery.img-service.net";
        let (cat, _) = classify_connection(443, Some(domain));
        assert_eq!(cat, TrafficCategory::GeneralApi);

        // 模拟 3 次大下行图片流 (上行 500B, 下行 64KB)
        for _ in 0..3 {
            record_conn_metrics(Some(domain), 500, 65536, 1500, CloseReason::ServerClosed, false);
        }

        let (cat_learned, _) = classify_connection(443, Some(domain));
        assert_eq!(cat_learned, TrafficCategory::MediaCdn);
        assert_eq!(compute_adaptive_timeout(443, Some(domain), true).as_secs(), 10);
    }

    #[test]
    fn test_dynamic_learning_to_push_im() {
        let domain = "custom-socket.message-hub.io";
        let (cat, _) = classify_connection(443, Some(domain));
        assert_eq!(cat, TrafficCategory::GeneralApi);

        // 模拟 3 次心跳小包 (上行 120B, 下行 180B, 持续 10 秒)
        for _ in 0..3 {
            record_conn_metrics(Some(domain), 120, 180, 10000, CloseReason::ClientClosed, true);
        }

        let (cat_learned, _) = classify_connection(443, Some(domain));
        assert_eq!(cat_learned, TrafficCategory::PushIm);
        assert_eq!(compute_adaptive_timeout(443, Some(domain), true).as_secs(), 180);
    }

    #[test]
    fn test_churn_penalty() {
        let domain = "test-churn-app.api.io";
        let _ = classify_connection(443, Some(domain));

        // 1. 连接正常交互后由于短超时关闭 (上行 4KB, 下行 6KB, 持续 2s)
        record_conn_metrics(Some(domain), 4096, 6144, 2000, CloseReason::IdleTimeout, true);

        // 2. 模拟 App 立即发生重连 (由于刚刚被 IdleTimeout 掐断, 触发 1.5x 惩罚)
        let (_, timeout_after_churn) = classify_connection(443, Some(domain));
        // 初始 GeneralApi 为 30s，触发 1.5x 惩罚后应为 45s
        assert_eq!(timeout_after_churn.as_secs(), 45);
    }

    #[test]
    fn test_zombie_decay() {
        let domain = "test-zombie-app.api.io";
        let _ = classify_connection(443, Some(domain));

        // 连接被 IdleTimeout 关闭且全程未被复用 (is_reused = false) -> 触发 0.8x 僵尸衰减
        record_conn_metrics(Some(domain), 4096, 6144, 2000, CloseReason::IdleTimeout, false);

        // 验证画像内 assigned_idle_secs 已缩减: 30 * 0.8 = 24s
        let profile = with_profiles_read(|map| map.get(domain).cloned()).unwrap();
        assert_eq!(profile.assigned_idle_secs, 24);
        assert_eq!(profile.zombie_decays, 1);
    }

    #[test]
    fn test_static_rule_immunity_from_churn_and_decay() {
        let static_cdn = "pbs.twimg.com";
        let (cat, timeout) = classify_connection(443, Some(static_cdn));
        assert_eq!(cat, TrafficCategory::MediaCdn);
        assert_eq!(timeout.as_secs(), 10);

        // 1. 模拟 IdleTimeout 且无复用 -> 静态规则不应被 Zombie 衰减
        record_conn_metrics(Some(static_cdn), 500, 20000, 1000, CloseReason::IdleTimeout, false);
        let (_, timeout_after_idle) = classify_connection(443, Some(static_cdn));
        assert_eq!(timeout_after_idle.as_secs(), 10);

        // 2. 模拟 1 秒后立即重连 -> 静态规则不应被 Churn 惩罚放大
        let (_, timeout_after_reconnect) = classify_connection(443, Some(static_cdn));
        assert_eq!(timeout_after_reconnect.as_secs(), 10);

        let profile = with_profiles_read(|map| map.get(static_cdn).cloned()).unwrap();
        assert_eq!(profile.assigned_idle_secs, 10);
        assert_eq!(profile.churn_penalties, 0);
        assert_eq!(profile.zombie_decays, 0);
    }

    #[test]
    fn test_disk_persistence() {
        let test_file = "/tmp/test_traffic_profiles.json";
        record_conn_metrics(Some("persist.example.com"), 1234, 5678, 2000, CloseReason::ServerClosed, true);
        save_profiles_to_disk(test_file).expect("Save to disk should succeed");

        assert!(std::path::Path::new(test_file).exists());
        let count = load_profiles_from_disk(test_file).expect("Load from disk should succeed");
        assert!(count >= 1);
        let _ = std::fs::remove_file(test_file);
    }
}

