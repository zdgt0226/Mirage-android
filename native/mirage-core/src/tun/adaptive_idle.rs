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
//! 3. **记录与洞察生成 (Profiles Record & Statistics)**：
//!    - 维护全局内存 LRU / 学习画像表（最多保留 512 个常用域名画像）。
//!    - 提供 `get_learned_profiles_json()` 供 JNI / 控制台 / 监控面板查询已学得的时延与分类设定。

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

/// 判定连接分类并返回推荐的空闲超时
pub fn classify_connection(dst_port: u16, domain: Option<&str>) -> (TrafficCategory, Duration) {
    if is_interactive_port(dst_port) {
        return (TrafficCategory::Interactive, TrafficCategory::Interactive.idle_timeout(false));
    }

    if let Some(dom) = domain {
        let dom_lower = dom.to_lowercase();
        // 1. 查询是否已存在画像记录
        if let Some(profile) = with_profiles_read(|map| map.get(&dom_lower).cloned()) {
            return (profile.category, profile.category.idle_timeout(false));
        }

        // 2. 静态规则快速命中
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
            // 清理最早未更新的一个条目
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
            assigned_idle_secs: category.idle_timeout(true).as_secs(),
            is_static_rule: is_static,
            last_seen_secs: unix_now_secs(),
        });
    });
}

/// 计算自适应超时时长
pub fn compute_adaptive_timeout(
    dst_port: u16,
    domain: Option<&str>,
    is_active_transfer: bool,
) -> Duration {
    let (cat, _) = classify_connection(dst_port, domain);
    cat.idle_timeout(is_active_transfer)
}

/// 记录一次连接生命周期的吞吐指标并触发自适应学习画像演进
pub fn record_conn_metrics(
    domain: Option<&str>,
    up_bytes: u64,
    down_bytes: u64,
    duration_ms: u64,
) {
    let Some(dom) = domain else { return };
    if dom.is_empty() { return; }
    let dom_lower = dom.to_lowercase();

    with_profiles_write(|map| {
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
                assigned_idle_secs: initial_cat.idle_timeout(true).as_secs(),
                is_static_rule: initial_cat != TrafficCategory::GeneralApi,
                last_seen_secs: unix_now_secs(),
            }
        });

        profile.sample_count += 1;
        profile.total_up_bytes += up_bytes;
        profile.total_down_bytes += down_bytes;
        profile.last_seen_secs = unix_now_secs();

        let ratio = profile.total_down_bytes as f32 / (profile.total_up_bytes.max(1) as f32);
        profile.avg_down_up_ratio = ratio;

        // 如果不是静态锁定的规则且样本数 >= 2，进行自适应学习演进
        if !profile.is_static_rule && profile.sample_count >= 2 {
            let avg_down = profile.total_down_bytes / profile.sample_count;
            let avg_up = profile.total_up_bytes / profile.sample_count;

            let prev_cat = profile.category;

            // 特征 1: 高下载/上行比且单次吞吐较大 -> 自动演进为 MediaCdn (10s 快速回收)
            if ratio >= 6.0 && avg_down >= 16 * 1024 {
                profile.category = TrafficCategory::MediaCdn;
            }
            // 特征 2: 双向持续微量小包且连接持续时间较长 -> 自动演进为 PushIm (180s 长保活)
            else if avg_up <= 2048 && avg_down <= 4096 && duration_ms >= 8000 {
                profile.category = TrafficCategory::PushIm;
            }
            // 其余 -> GeneralApi (30s)
            else {
                profile.category = TrafficCategory::GeneralApi;
            }

            profile.assigned_idle_secs = profile.category.idle_timeout(true).as_secs();

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
        // 初始分类为 GeneralApi
        let (cat, _) = classify_connection(443, Some(domain));
        assert_eq!(cat, TrafficCategory::GeneralApi);

        // 模拟 3 次大下行图片流 (上行 500B, 下行 64KB)
        for _ in 0..3 {
            record_conn_metrics(Some(domain), 500, 65536, 1500);
        }

        // 验证已自动演进为 MediaCdn
        let (cat_learned, timeout) = classify_connection(443, Some(domain));
        assert_eq!(cat_learned, TrafficCategory::MediaCdn);
        assert_eq!(timeout.as_secs(), 15); // 首包等待 15s
        assert_eq!(compute_adaptive_timeout(443, Some(domain), true).as_secs(), 10); // 传输后 10s 回收
    }

    #[test]
    fn test_dynamic_learning_to_push_im() {
        let domain = "custom-socket.message-hub.io";
        let (cat, _) = classify_connection(443, Some(domain));
        assert_eq!(cat, TrafficCategory::GeneralApi);

        // 模拟 3 次心跳小包 (上行 120B, 下行 180B, 持续 10 秒)
        for _ in 0..3 {
            record_conn_metrics(Some(domain), 120, 180, 10000);
        }

        let (cat_learned, timeout) = classify_connection(443, Some(domain));
        assert_eq!(cat_learned, TrafficCategory::PushIm);
        assert_eq!(timeout.as_secs(), 180);
    }

    #[test]
    fn test_json_export() {
        record_conn_metrics(Some("sample.test.com"), 100, 200, 500);
        let json = get_learned_profiles_json();
        assert!(json.contains("sample.test.com"));
    }
}
