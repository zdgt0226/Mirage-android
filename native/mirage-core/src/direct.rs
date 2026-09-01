//! 路由规则引擎: 复合多条件判定 (直连 vs 代理 vs 拦截)。
//!
//! 100% 由 Geo 动态规则库 (geosite.dat / geoip.dat) 与用户自定义复合规则驱动。
//! 支持:
//! - 复合条件组合 (AND / OR 逻辑算子)
//! - 多维度匹配: GeoSite, GeoIP, Domain (Suffix/Exact/Keyword/Regex), IP-CIDR, Port/Port-Range, Protocol (TCP/UDP)
//! - 规则启用/禁用状态 (Enabled)
//! - 首条命中即生效 (First Match Wins)

use std::net::IpAddr;
use std::sync::{OnceLock, RwLock};
use tracing::debug;

use crate::geo::{match_geoip_code, match_geosite_tag, Ipv4Cidr};

/// 规则动作
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum RuleAction {
    Direct,
    #[default]
    Proxy,
    Block,
}

impl RuleAction {
    pub fn as_str(&self) -> &'static str {
        match self {
            RuleAction::Direct => "direct",
            RuleAction::Proxy => "proxy",
            RuleAction::Block => "block",
        }
    }

    pub fn from_str(s: &str) -> Self {
        match s.trim().to_ascii_lowercase().as_str() {
            "direct" => RuleAction::Direct,
            "block" | "reject" => RuleAction::Block,
            _ => RuleAction::Proxy,
        }
    }
}

/// 复合逻辑算子
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum MatchLogic {
    #[default]
    Or,
    And,
}

impl MatchLogic {
    pub fn from_str(s: &str) -> Self {
        match s.trim().to_ascii_uppercase().as_str() {
            "AND" => MatchLogic::And,
            _ => MatchLogic::Or,
        }
    }
}

/// 单个原子匹配条件
#[derive(Debug, Clone)]
pub enum ConditionKind {
    GeoSite(String),
    GeoIp(String),
    DomainSuffix(String),
    DomainExact(String),
    DomainKeyword(String),
    DomainRegex(String, Option<regex::Regex>),
    IpCidr(String, Option<Ipv4Cidr>),
    Port(Vec<u16>, Vec<(u16, u16)>), // 单端口列表 + 端口范围区间 (min, max)
    Protocol(String),                // "tcp" | "udp"
}

impl ConditionKind {
    pub fn matches(&self, domain: Option<&str>, ip: Option<IpAddr>, port: Option<u16>, protocol: Option<&str>) -> bool {
        match self {
            ConditionKind::GeoSite(tag) => {
                if let Some(d) = domain {
                    match_geosite_tag(tag, d)
                } else {
                    false
                }
            }
            ConditionKind::GeoIp(code) => {
                if let Some(ip_addr) = ip {
                    if is_fake_ip(ip_addr) {
                        return false;
                    }
                    match_geoip_code(code, ip_addr)
                } else {
                    false
                }
            }
            ConditionKind::DomainSuffix(s) => {
                if let Some(d) = domain {
                    let d_lower = d.trim_end_matches('.').to_ascii_lowercase();
                    d_lower == *s || d_lower.ends_with(&format!(".{s}"))
                } else {
                    false
                }
            }
            ConditionKind::DomainExact(s) => {
                if let Some(d) = domain {
                    d.trim_end_matches('.').eq_ignore_ascii_case(s)
                } else {
                    false
                }
            }
            ConditionKind::DomainKeyword(s) => {
                if let Some(d) = domain {
                    d.to_ascii_lowercase().contains(s)
                } else {
                    false
                }
            }
            ConditionKind::DomainRegex(raw, re) => {
                if let Some(d) = domain {
                    if let Some(r) = re {
                        r.is_match(d)
                    } else {
                        d.contains(raw)
                    }
                } else {
                    false
                }
            }
            ConditionKind::IpCidr(_, cidr) => {
                if let (Some(IpAddr::V4(v4)), Some(c)) = (ip, cidr) {
                    if is_fake_ip(IpAddr::V4(v4)) {
                        return false;
                    }
                    c.contains(v4)
                } else {
                    false
                }
            }
            ConditionKind::Port(singles, ranges) => {
                if let Some(p) = port {
                    if singles.contains(&p) {
                        return true;
                    }
                    for &(start, end) in ranges {
                        if p >= start && p <= end {
                            return true;
                        }
                    }
                }
                false
            }
            ConditionKind::Protocol(proto) => {
                if let Some(p) = protocol {
                    p.eq_ignore_ascii_case(proto)
                } else {
                    false
                }
            }
        }
    }
}

/// 复合规则实体
#[derive(Debug, Clone)]
pub struct CompositeRule {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub logic: MatchLogic,
    pub conditions: Vec<ConditionKind>,
    pub action: RuleAction,
}

impl CompositeRule {
    pub fn matches(&self, domain: Option<&str>, ip: Option<IpAddr>, port: Option<u16>, protocol: Option<&str>) -> bool {
        if !self.enabled || self.conditions.is_empty() {
            return false;
        }

        match self.logic {
            MatchLogic::Or => {
                for c in &self.conditions {
                    if c.matches(domain, ip, port, protocol) {
                        return true;
                    }
                }
                false
            }
            MatchLogic::And => {
                for c in &self.conditions {
                    if !c.matches(domain, ip, port, protocol) {
                        return false;
                    }
                }
                true
            }
        }
    }
}

/// 全局规则仓库
#[derive(Default)]
pub struct RouterStore {
    pub rules: Vec<CompositeRule>,
    pub default_action: RuleAction,
}

fn router_store() -> &'static RwLock<RouterStore> {
    static R: OnceLock<RwLock<RouterStore>> = OnceLock::new();
    R.get_or_init(|| RwLock::new(RouterStore::default()))
}

/// 直连 IP 集合: DNS 分流把"直连域名"解析出的真实 IP 记到这里
fn direct_ips() -> &'static std::sync::Mutex<std::collections::HashSet<IpAddr>> {
    static S: OnceLock<std::sync::Mutex<std::collections::HashSet<IpAddr>>> = OnceLock::new();
    S.get_or_init(|| std::sync::Mutex::new(std::collections::HashSet::new()))
}

static BLOCK_QUIC: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(true);

pub fn set_block_quic(block: bool) {
    BLOCK_QUIC.store(block, std::sync::atomic::Ordering::Relaxed);
}

pub fn is_block_quic() -> bool {
    BLOCK_QUIC.load(std::sync::atomic::Ordering::Relaxed)
}

/// 判断是否为 Fake-IP 虚拟保留地址 (198.18.0.0/15, 即 198.18.0.0 ~ 198.19.255.255)
#[inline]
pub fn is_fake_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => (u32::from(v4) & 0xFFFE0000) == 0xC6120000,
        _ => false,
    }
}

pub fn mark_direct_ip(ip: IpAddr) {
    if !is_fake_ip(ip) {
        direct_ips().lock().unwrap_or_else(|e| e.into_inner()).insert(ip);
    }
}

pub fn is_direct_ip(ip: IpAddr) -> bool {
    if is_fake_ip(ip) {
        return false;
    }
    direct_ips().lock().unwrap_or_else(|e| e.into_inner()).contains(&ip)
}

/// 判断 IP 是否为私有 IP / 局域网保留 IP (RFC 1918 / RFC 3927 / CGNAT / 组播 / 广播)
///
/// ⚠️ 绝不可走代理: 任何发往局域网路由器、NAS、打印机、智能家居设备的私有单播与组播必须走本地直连。
pub fn is_private_ip(ip: IpAddr) -> bool {
    if is_fake_ip(ip) {
        return false;
    }
    match ip {
        IpAddr::V4(v4) => {
            let u = u32::from(v4);
            // 10.0.0.0/8 (RFC 1918)
            (u & 0xFF000000) == 0x0A000000
            // 172.16.0.0/12 (RFC 1918)
            || (u & 0xFFF00000) == 0xAC100000
            // 192.168.0.0/16 (RFC 1918)
            || (u & 0xFFFF0000) == 0xC0A80000
            // 127.0.0.0/8 (Loopback)
            || (u & 0xFF000000) == 0x7F000000
            // 169.254.0.0/16 (Link-Local RFC 3927)
            || (u & 0xFFFF0000) == 0xA9FE0000
            // 100.64.0.0/10 (CGNAT RFC 6598)
            || (u & 0xFFC00000) == 0x64400000
            // 224.0.0.0/4 (Multicast RFC 5771)
            || (u & 0xF0000000) == 0xE0000000
            // 240.0.0.0/4 (Reserved / Broadcast RFC 1112)
            || (u & 0xF0000000) == 0xF0000000
            // 0.0.0.0/8 ("This network" RFC 1122)
            || (u & 0xFF000000) == 0x00000000
        }
        IpAddr::V6(v6) => {
            let segs = v6.segments();
            // ::1 (Loopback) or :: (Unspecified)
            v6.is_loopback() || v6.is_unspecified()
            // fe80::/10 (Link-Local Unicast)
            || (segs[0] & 0xFFC0) == 0xFE80
            // fc00::/7 (Unique Local Address ULA)
            || (segs[0] & 0xFE00) == 0xFC00
            // ff00::/8 (Multicast)
            || (segs[0] & 0xFF00) == 0xFF00
            // ::ffff:0:0/96 (IPv4-mapped IPv6)
            || (segs[0] == 0 && segs[1] == 0 && segs[2] == 0 && segs[3] == 0 && segs[4] == 0 && segs[5] == 0xFFFF && {
                let v4 = std::net::Ipv4Addr::new(
                    (segs[6] >> 8) as u8,
                    (segs[6] & 0xFF) as u8,
                    (segs[7] >> 8) as u8,
                    (segs[7] & 0xFF) as u8,
                );
                is_private_ip(IpAddr::V4(v4))
            })
        }
    }
}

/// 静态局域网与路由器管理域名列表
const LAN_ROUTER_EXACT_DOMAINS: &[&str] = &[
    "router.asus.com",
    "asusrouter.com",
    "tplogin.cn",
    "tplinkwifi.net",
    "tplinklogin.net",
    "miwifi.com",
    "tendawifi.com",
    "pcloudlink.com",
    "phicomm.me",
    "zte.home",
    "my.router",
    "netcore.cc",
    "melogin.cn",
    "falogin.cn",
    "hiwifi.com",
    "leike.cc",
    "routerlogin.net",
    "routerlogin.com",
    "speedport.ip",
    "fritz.box",
    "repeater.setup",
    "router.ctc",
    "gateway.zte",
];

const LAN_DOMAIN_SUFFIXES: &[&str] = &[
    "local",
    "lan",
    "home.arpa",
    "localhost",
    "internal",
    "home",
    "corp",
    "box",
];

/// 判断域名是否属于局域网主机名、mDNS 或主流路由器后台管理域名
pub fn is_lan_or_router_domain(domain: &str) -> bool {
    let d = domain.trim_end_matches('.').to_ascii_lowercase();
    if LAN_ROUTER_EXACT_DOMAINS.iter().any(|&r| d == r || d.ends_with(&format!(".{r}"))) {
        return true;
    }
    for &s in LAN_DOMAIN_SUFFIXES {
        if d == s || d.ends_with(&format!(".{s}")) {
            return true;
        }
    }
    false
}

/// 局域网主流路由器管理域名默认默认回退 IP
pub fn default_router_ip_for_domain(domain: &str) -> Option<IpAddr> {
    let d = domain.trim_end_matches('.').to_ascii_lowercase();
    if d.contains("miwifi.com") {
        Some(IpAddr::V4(std::net::Ipv4Addr::new(192, 168, 31, 1)))
    } else if d.contains("tendawifi.com") {
        Some(IpAddr::V4(std::net::Ipv4Addr::new(192, 168, 0, 1)))
    } else if d.contains("phicomm.me") || d.contains("speedport.ip") {
        Some(IpAddr::V4(std::net::Ipv4Addr::new(192, 168, 2, 1)))
    } else if d.contains("fritz.box") {
        Some(IpAddr::V4(std::net::Ipv4Addr::new(192, 168, 178, 1)))
    } else if d.contains("router.asus.com") || d.contains("asusrouter.com") || d.contains("tplogin.cn")
        || d.contains("tplinkwifi.net") || d.contains("melogin.cn") || d.contains("falogin.cn")
        || d.contains("hiwifi.com") || d.contains("netcore.cc") || d.contains("leike.cc")
    {
        Some(IpAddr::V4(std::net::Ipv4Addr::new(192, 168, 1, 1)))
    } else {
        None
    }
}

/// 判断 IP 是否属于中国段 (优先查动态加载的 GeoIP: CN，未就绪时二分查找内置 7730 条 CN IP 段)
///
/// ⚠️ 二分正确性前提: 不能用 partition_point 按 net 直接二分 —— CN_IPV4 的 net 并非全部
/// 对齐 CIDR 基点且段间存在重叠 (实测 20 万随机 IP 采样有 ~0.6% 误判)。必须先把每段转成
/// 真实区间 [start, end] 并合并重叠, 再对合并后的互斥区间二分 (与 R1 修复同思路)。
pub fn is_cn_ip(ip: IpAddr) -> bool {
    if is_fake_ip(ip) {
        return false;
    }
    if match_geoip_code("CN", ip) {
        return true;
    }
    if let IpAddr::V4(v4) = ip {
        let target = u32::from(v4);
        static CN_RANGES: std::sync::LazyLock<Vec<(u32, u32)>> = std::sync::LazyLock::new(|| {
            // 每段 → 真实 [start, end] (按 CIDR 语义: 网络号相同即命中)
            let mut ranges: Vec<(u32, u32)> = crate::direct_cn_ipv4::CN_IPV4
                .iter()
                .map(|&(net, prefix)| {
                    let mask = if prefix == 0 { 0 } else { !0u32 << (32 - prefix) };
                    let start = net & mask;
                    let end = start | !mask;
                    (start, end)
                })
                .collect();
            ranges.sort_unstable_by_key(|&(start, _)| start);
            // 合并重叠与相邻区间 (保证互斥, 二分正确)
            let mut merged: Vec<(u32, u32)> = Vec::with_capacity(ranges.len());
            for (s, e) in ranges {
                if let Some(last) = merged.last_mut() {
                    if s <= last.1.saturating_add(1) {
                        if e > last.1 { last.1 = e; }
                        continue;
                    }
                }
                merged.push((s, e));
            }
            merged
        });
        let found = CN_RANGES.binary_search_by(|&(start, end)| {
            if target < start {
                std::cmp::Ordering::Greater
            } else if target > end {
                std::cmp::Ordering::Less
            } else {
                std::cmp::Ordering::Equal
            }
        });
        if found.is_ok() {
            return true;
        }
    } else if let IpAddr::V6(v6) = ip {
        let segs = v6.segments();
        let top = segs[0];
        // 中国大陆主要 IPv6 运营商/教育/科研地址块:
        // - 2408::/16 (中国联通)
        // - 2409::/16 (中国移动)
        // - 240a::/16 (CERNET 教育网)
        // - 240b::/16 (CSTNET 科技网)
        // - 240c::/16, 240d::/16, 240e::/16 (中国电信)
        // - 240f::/16 (中国铁通)
        // - 2400::/12 (亚太/中国联合分配块)
        // - 2001:da8::/32 (CERNET2)
        // - 2001:250::/32 (CNGrid 科技网)
        if (top >= 0x2408 && top <= 0x240f)
            || (top >= 0x2400 && top <= 0x2403)
            || (top == 0x2001 && (segs[1] == 0xda8 || segs[1] == 0x250))
        {
            return true;
        }
    }
    false
}

/// 已知典型境外主流服务根域名 (防止 GeoSite:CN 上游数据库污染误将 Google/YouTube/Telegram/X 等判定为国内直连)
pub fn is_known_non_cn_domain(domain: &str) -> bool {
    let d = domain.trim_end_matches('.').to_ascii_lowercase();
    const NON_CN_ROOTS: &[&str] = &[
        "google.com", "googleapis.com", "googlevideo.com", "gstatic.com", "ggpht.com",
        "gvt1.com", "gvt2.com", "1e100.net", "googleusercontent.com",
        "googleadservices.com", "googlesyndication.com", "google-analytics.com", "doubleclick.net",
        "youtube.com", "youtu.be", "ytimg.com", "yt.be",
        "android.com", "g.co", "goo.gl",
        "telegram.org", "t.me", "telesco.pe", "tdesktop.com",
        "twitter.com", "x.com", "twimg.com", "t.co",
        "facebook.com", "fbcdn.net", "instagram.com", "cdninstagram.com", "whatsapp.com", "threads.net",
        "netflix.com", "nflxvideo.net", "nflximg.net",
        "spotify.com", "scdn.co", "spotifycdn.com",
        "github.com", "githubusercontent.com", "github.io", "git.io",
        "openai.com", "chatgpt.com", "oaistatic.com", "oaiusercontent.com",
        "anthropic.com", "claude.ai",
        "tiktok.com", "tiktokv.com", "byteoversea.com", "ibytedtos.com", "musical.ly",
        "wikipedia.org", "wikimedia.org",
        "discord.com", "discordapp.com", "discord.gg",
    ];
    for &root in NON_CN_ROOTS {
        if d == root || d.ends_with(&format!(".{root}")) {
            return true;
        }
    }
    false
}

/// 严格国内域名判定 (只查内置白名单 + .cn 后缀, 不查 Geo 数据)。
pub fn is_cn_domain_strict(domain: &str) -> bool {
    if is_known_non_cn_domain(domain) {
        return false;
    }
    let d_lower = domain.trim_end_matches('.').to_ascii_lowercase();
    if d_lower.ends_with(".cn") || d_lower == "cn" {
        return true;
    }
    for &d in crate::direct_cn_domains::CN_DOMAINS {
        if d_lower == d || d_lower.ends_with(&format!(".{d}")) {
            return true;
        }
    }
    false
}

pub fn is_cn_domain(domain: &str) -> bool {
    if is_known_non_cn_domain(domain) {
        return false;
    }
    if match_geosite_tag("CN", domain) {
        return true;
    }
    let d_lower = domain.trim_end_matches('.').to_ascii_lowercase();
    if d_lower.ends_with(".cn") || d_lower == "cn" {
        return true;
    }
    for &d in crate::direct_cn_domains::CN_DOMAINS {
        if d_lower == d || d_lower.ends_with(&format!(".{d}")) {
            return true;
        }
    }
    false
}

pub fn builtin_domains() -> Vec<String> {
    crate::direct_cn_domains::CN_DOMAINS.iter().map(|s| s.to_string()).collect()
}

pub fn builtin_ip_count() -> usize {
    crate::direct_cn_ipv4::CN_IPV4.len()
}

/// 规则命中统计: key = "rule_id|name|action", 值 = 命中次数
fn rule_hits() -> &'static std::sync::Mutex<std::collections::HashMap<String, std::sync::atomic::AtomicU64>> {
    static H: OnceLock<std::sync::Mutex<std::collections::HashMap<String, std::sync::atomic::AtomicU64>>> =
        OnceLock::new();
    H.get_or_init(|| std::sync::Mutex::new(std::collections::HashMap::new()))
}

fn record_rule_hit(id: &str, name: &str, action: &str) {
    let key = format!("{id}|{name}|{action}");
    let mut map = rule_hits().lock().unwrap_or_else(|e| e.into_inner());
    map.entry(key).or_default().fetch_add(1, std::sync::atomic::Ordering::Relaxed);
}

pub fn get_rule_hits() -> String {
    let map = rule_hits().lock().unwrap_or_else(|e| e.into_inner());
    let mut list: Vec<serde_json::Value> = Vec::new();
    for (key, hits) in map.iter() {
        let mut parts = key.splitn(3, '|');
        if let (Some(id), Some(name), Some(action)) =
            (parts.next(), parts.next(), parts.next())
        {
            list.push(serde_json::json!({
                "kind": id,
                "pattern": name,
                "action": action,
                "hits": hits.load(std::sync::atomic::Ordering::Relaxed),
            }));
        }
    }
    serde_json::to_string(&list).unwrap_or_else(|_| "[]".to_string())
}

pub fn reset_rule_hits() {
    rule_hits().lock().unwrap_or_else(|e| e.into_inner()).clear();
}

/// 出站模式 (Outbound Mode)
/// 0 = Rule (规则模式，默认)
/// 1 = GlobalProxy (全局代理模式)
/// 2 = Direct (全局直连模式)
static OUTBOUND_MODE: std::sync::atomic::AtomicU8 = std::sync::atomic::AtomicU8::new(0);

pub fn set_outbound_mode(mode: u8) {
    OUTBOUND_MODE.store(mode, std::sync::atomic::Ordering::Relaxed);
    tracing::info!("[ROUTER] 出站模式已切换为: {}", match mode {
        1 => "全局代理 (GlobalProxy)",
        2 => "直接连接 (Direct)",
        _ => "规则分流 (Rule)",
    });
}

pub fn get_outbound_mode() -> u8 {
    OUTBOUND_MODE.load(std::sync::atomic::Ordering::Relaxed)
}

/// 综合决策请求的目标动作与匹配细节 (用于 Surge 级 Recent Requests 请求流展示)
pub fn route_decision_detailed(
    domain: Option<&str>,
    ip: Option<IpAddr>,
    port: Option<u16>,
    protocol: Option<&str>,
) -> (RuleAction, String) {
    // 0. 私有 IP / 局域网主机与路由器后台域名内置本地直连 (最高保护，任何模式均不破坏 LAN)
    if let Some(ip_addr) = ip {
        if is_private_ip(ip_addr) {
            return (RuleAction::Direct, "Private IP (LAN)".to_string());
        }
    }
    if let Some(dom) = domain {
        if is_lan_or_router_domain(dom) {
            return (RuleAction::Direct, "Router / LAN Domain".to_string());
        }
    }

    // 1. 全局模式判断 (Global Proxy / Direct Override)
    let mode = get_outbound_mode();
    if mode == 1 {
        return (RuleAction::Proxy, "Global Proxy Override".to_string());
    } else if mode == 2 {
        return (RuleAction::Direct, "Global Direct Override".to_string());
    }

    let r = router_store().read().unwrap_or_else(|e| e.into_inner());

    // 2. 遍历用户自定义复合规则 (自上而下，首条命中即返回)
    for rule in &r.rules {
        if rule.matches(domain, ip, port, protocol) {
            let cn_direct = rule.action == RuleAction::Direct
                && rule.conditions.iter().any(|c| matches!(c, ConditionKind::GeoSite(t) if t.eq_ignore_ascii_case("cn")));
            if cn_direct {
                let strictly_cn = match domain {
                    Some(d) => is_cn_domain_strict(d),
                    None => ip.is_some_and(|i| is_cn_ip(i)),
                };
                if !strictly_cn {
                    tracing::debug!(
                        "[ROUTER] geosite:cn 命中但非严格国内 ({:?}), 跳过该规则继续匹配",
                        domain.map(|d| d.to_string())
                            .or(ip.map(|i| i.to_string()))
                            .unwrap_or_default()
                    );
                    continue;
                }
            }
            record_rule_hit(&rule.id, &rule.name, rule.action.as_str());
            return (rule.action, format!("Rule: {}", rule.name));
        }
    }

    // 3. 检查直连 IP 标记 (DNS 阶段标记的直连真实 IP)
    if let Some(ip_addr) = ip {
        if is_direct_ip(ip_addr) {
            return (RuleAction::Direct, "Direct IP (DNS Tagged)".to_string());
        }
    }

    // 4. 国内流量智能直连兜底 (在用户未配置规则/冷启动状态下，确保国内域名/IP 默认直连)
    if let Some(dom) = domain {
        if is_cn_domain(dom) {
            return (RuleAction::Direct, "CN Domain (Direct)".to_string());
        }
    }
    if let Some(ip_addr) = ip {
        if is_cn_ip(ip_addr) {
            return (RuleAction::Direct, "CN IP (Direct)".to_string());
        }
    }

    // 5. 回退至默认动作 (境外未命中规则默认走 proxy)
    let action = r.default_action;
    (action, format!("Default {:?}", action))
}

/// 综合决策请求的目标动作 (支持传入 域名、IP、端口、协议)
pub fn route_decision(
    domain: Option<&str>,
    ip: Option<IpAddr>,
    port: Option<u16>,
    protocol: Option<&str>,
) -> RuleAction {
    route_decision_detailed(domain, ip, port, protocol).0
}

/// 域名决策 (DNS 阶段使用)
pub fn decide_domain(domain: &str) -> RuleAction {
    route_decision(Some(domain), None, None, None)
}

/// IP 决策 (TCP/UDP 阶段使用)
pub fn decide_ip(ip: IpAddr) -> RuleAction {
    route_decision(None, Some(ip), None, None)
}

/// 解析单端口或端口范围字符串 (如 "80,443,8000-8080")
fn parse_ports(s: &str) -> (Vec<u16>, Vec<(u16, u16)>) {
    let mut singles = Vec::new();
    let mut ranges = Vec::new();

    for part in s.split(',') {
        let trimmed = part.trim();
        if let Some(dash_idx) = trimmed.find('-') {
            let start = trimmed[..dash_idx].trim().parse::<u16>().ok();
            let end = trimmed[dash_idx + 1..].trim().parse::<u16>().ok();
            if let (Some(s), Some(e)) = (start, end) {
                if s <= e {
                    ranges.push((s, e));
                }
            }
        } else if let Ok(p) = trimmed.parse::<u16>() {
            singles.push(p);
        }
    }

    (singles, ranges)
}

/// 安全清洗并注入系统保护规则 (对齐 meow strip_and_inject 设计)
///
/// 1. 过滤掉语法无效、空条件、非法的脏规则
/// 2. 注入顶级优先保护规则:
///    - LAN 私有网段 (RFC 1918 / 路由器后台域名) 强制直连保护
/// 3. 注入 Fake-IP 虚拟网段代理保护 (198.18.0.0/15 -> Proxy)
/// 4. 兜底注入统一默认动作
pub fn strip_and_inject_rules(raw_rules_json: &str) -> String {
    let parsed: serde_json::Value = match serde_json::from_str(raw_rules_json) {
        Ok(v) => v,
        Err(_) => serde_json::json!({
            "rules": [],
            "default_action": "proxy"
        }),
    };

    let default_action = parsed.get("default_action")
        .and_then(|x| x.as_str())
        .unwrap_or("proxy");

    let mut clean_rules: Vec<serde_json::Value> = Vec::new();

    // ── 注入 1: 强制系统最高优先级保护规则 (LAN 私有网段与路由器直连) ──
    clean_rules.push(serde_json::json!({
        "id": "sys_lan_private_direct",
        "name": "局域网与路由器私有地址直连 (系统保护)",
        "enabled": true,
        "action": "direct",
        "logic": "OR",
        "conditions": [
            { "type": "geoip", "pattern": "PRIVATE" },
            { "type": "geosite", "pattern": "PRIVATE" }
        ]
    }));

    // ── 注入 2: 过滤与清洗用户规则 ──
    if let Some(arr) = parsed.get("rules").and_then(|a| a.as_array()) {
        for (idx, item) in arr.iter().enumerate() {
            let id = item.get("id").and_then(|x| x.as_str()).unwrap_or("");
            if id.starts_with("sys_") {
                continue; // 避免重复注入
            }
            let enabled = item.get("enabled").and_then(|x| x.as_bool()).unwrap_or(true);
            let action = item.get("action").and_then(|x| x.as_str()).unwrap_or("proxy");
            let logic = item.get("logic").and_then(|x| x.as_str()).unwrap_or("OR");
            let name = item.get("name").and_then(|x| x.as_str()).unwrap_or("自定义规则");

            let mut valid_conditions = Vec::new();

            if let Some(cond_arr) = item.get("conditions").and_then(|c| c.as_array()) {
                for cond in cond_arr {
                    let ctype = cond.get("type").and_then(|x| x.as_str()).unwrap_or("").trim();
                    let cpat = cond.get("pattern").and_then(|x| x.as_str()).unwrap_or("").trim();
                    if !ctype.is_empty() && !cpat.is_empty() {
                        if parse_condition_kind(ctype, cpat).is_some() {
                            valid_conditions.push(serde_json::json!({
                                "type": ctype,
                                "pattern": cpat
                            }));
                        }
                    }
                }
            } else if let (Some(kind), Some(pattern)) = (
                item.get("kind").or_else(|| item.get("type")).and_then(|x| x.as_str()),
                item.get("pattern").and_then(|x| x.as_str())
            ) {
                if !kind.trim().is_empty() && !pattern.trim().is_empty() {
                    if parse_condition_kind(kind, pattern).is_some() {
                        valid_conditions.push(serde_json::json!({
                            "type": kind.trim(),
                            "pattern": pattern.trim()
                        }));
                    }
                }
            }

            if !valid_conditions.is_empty() {
                clean_rules.push(serde_json::json!({
                    "id": if id.is_empty() { format!("user_rule_{idx}") } else { id.to_string() },
                    "name": name,
                    "enabled": enabled,
                    "action": action,
                    "logic": logic,
                    "conditions": valid_conditions
                }));
            }
        }
    }

    // ── 注入 3: 注入 Fake-IP 虚拟网段代理保护 ──
    clean_rules.push(serde_json::json!({
        "id": "sys_fake_ip_guard",
        "name": "Fake-IP 虚拟网段代理保护 (系统保护)",
        "enabled": true,
        "action": "proxy",
        "logic": "OR",
        "conditions": [
            { "type": "cidr", "pattern": "198.18.0.0/15" }
        ]
    }));

    let result = serde_json::json!({
        "rules": clean_rules,
        "default_action": default_action
    });

    serde_json::to_string(&result).unwrap_or_else(|_| raw_rules_json.to_string())
}

/// 设置自定义规则 (经过 strip_and_inject 安全清洗与注入)
pub fn set_custom_rules(json: &str) -> bool {
    let sanitized = strip_and_inject_rules(json);
    let parsed: Result<serde_json::Value, _> = serde_json::from_str(&sanitized);
    let Ok(v) = parsed else { return false };
    let mut new_rules = Vec::new();

    let default_action = v.get("default_action")
        .and_then(|x| x.as_str())
        .map(RuleAction::from_str)
        .unwrap_or(RuleAction::Proxy);

    if let Some(arr) = v.get("rules").and_then(|a| a.as_array()) {
        for (idx, item) in arr.iter().enumerate() {
            let enabled = item.get("enabled").and_then(|x| x.as_bool()).unwrap_or(true);
            let action_str = item.get("action").and_then(|x| x.as_str()).unwrap_or("proxy");
            let action = RuleAction::from_str(action_str);
            let id = item.get("id").and_then(|x| x.as_str()).map(|s| s.to_string()).unwrap_or_else(|| format!("rule_{idx}"));
            let name = item.get("name").and_then(|x| x.as_str()).map(|s| s.to_string()).unwrap_or_else(|| {
                item.get("pattern").and_then(|x| x.as_str()).unwrap_or("自定义规则").to_string()
            });
            let logic_str = item.get("logic").and_then(|x| x.as_str()).unwrap_or("OR");
            let logic = MatchLogic::from_str(logic_str);

            let mut conditions = Vec::new();

            // 模式 A: 复合规则 (包含 "conditions" 数组)
            if let Some(cond_arr) = item.get("conditions").and_then(|c| c.as_array()) {
                for cond in cond_arr {
                    let (Some(ctype), Some(cpat)) = (
                        cond.get("type").and_then(|x| x.as_str()),
                        cond.get("pattern").and_then(|x| x.as_str())
                    ) else { continue };

                    if let Some(k) = parse_condition_kind(ctype, cpat) {
                        conditions.push(k);
                    }
                }
            } else if let (Some(kind), Some(pattern)) = (
                item.get("kind").or_else(|| item.get("type")).and_then(|x| x.as_str()),
                item.get("pattern").and_then(|x| x.as_str())
            ) {
                // 模式 B: 传统单条件规则兼容
                if let Some(k) = parse_condition_kind(kind, pattern) {
                    conditions.push(k);
                }
            }

            if !conditions.is_empty() {
                new_rules.push(CompositeRule {
                    id,
                    name,
                    enabled,
                    logic,
                    conditions,
                    action,
                });
            }
        }
    }

    // 清理已删除/已变更旧规则的命中统计，防止 HashMap 无界内存增长
    let valid_ids: std::collections::HashSet<String> = new_rules.iter().map(|r| r.id.clone()).collect();
    let mut hits = rule_hits().lock().unwrap_or_else(|e| e.into_inner());
    hits.retain(|k, _| {
        let id = k.split('|').next().unwrap_or("");
        valid_ids.contains(id)
    });
    drop(hits);

    let mut store = router_store().write().unwrap_or_else(|e| e.into_inner());
    store.rules = new_rules;
    store.default_action = default_action;
    debug!("[ROUTER] 路由规则已更新 (共 {} 条规则, 默认动作: {:?})", store.rules.len(), store.default_action);
    true
}

fn parse_condition_kind(kind: &str, pattern: &str) -> Option<ConditionKind> {
    let pat_lower = pattern.trim().to_ascii_lowercase();
    match kind.trim().to_ascii_lowercase().as_str() {
        "geosite" => Some(ConditionKind::GeoSite(pattern.trim().to_ascii_uppercase())),
        "geoip" => Some(ConditionKind::GeoIp(pattern.trim().to_ascii_uppercase())),
        "exact" | "domain_exact" => Some(ConditionKind::DomainExact(pat_lower)),
        "keyword" | "domain_keyword" => Some(ConditionKind::DomainKeyword(pat_lower)),
        "regex" | "domain_regex" => {
            let re = regex::Regex::new(pattern.trim()).ok();
            Some(ConditionKind::DomainRegex(pattern.to_string(), re))
        }
        "cidr" | "ip_cidr" => {
            let cidr = if let Some(slash) = pattern.find('/') {
                let ip_str = &pattern[..slash].trim();
                let prefix = pattern[slash + 1..].trim().parse::<u8>().unwrap_or(32);
                ip_str.parse::<std::net::Ipv4Addr>().ok().map(|ip| Ipv4Cidr::new(ip, prefix))
            } else {
                pattern.trim().parse::<std::net::Ipv4Addr>().ok().map(|ip| Ipv4Cidr::new(ip, 32))
            };
            Some(ConditionKind::IpCidr(pattern.to_string(), cidr))
        }
        "port" => {
            let (singles, ranges) = parse_ports(pattern);
            Some(ConditionKind::Port(singles, ranges))
        }
        "protocol" => Some(ConditionKind::Protocol(pat_lower)),
        _ => Some(ConditionKind::DomainSuffix(pat_lower)), // 默认 domain_suffix
    }
}

/// 判定是否应直连 (兼容旧调用)
pub fn should_direct(domain: Option<&str>, ip: Option<IpAddr>) -> bool {
    route_decision(domain, ip, None, None) == RuleAction::Direct
}

/// 判定是否应拦截 (兼容旧调用)
pub fn should_block(domain: Option<&str>, ip: Option<IpAddr>) -> bool {
    route_decision(domain, ip, None, None) == RuleAction::Block
}

/// 直连域名解析真实 IP
pub fn resolve_direct_domain(domain: &str) -> Option<IpAddr> {
    use std::net::ToSocketAddrs;
    format!("{domain}:80")
        .to_socket_addrs()
        .ok()
        .and_then(|mut iter| iter.next().map(|s| s.ip()))
}

#[cfg(test)]
mod tests {
    use super::*;
    static TEST_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

    #[test]
    fn test_composite_rule_and_or() {
        let _guard = TEST_LOCK.lock().unwrap();
        let json = r#"{
            "rules": [
                {
                    "id": "r1",
                    "name": "Google HTTPS AND TCP",
                    "enabled": true,
                    "logic": "AND",
                    "conditions": [
                        { "type": "domain_suffix", "pattern": "google.com" },
                        { "type": "port", "pattern": "443" },
                        { "type": "protocol", "pattern": "tcp" }
                    ],
                    "action": "proxy"
                },
                {
                    "id": "r2",
                    "name": "Local LAN",
                    "enabled": true,
                    "logic": "OR",
                    "conditions": [
                        { "type": "ip_cidr", "pattern": "192.168.0.0/16" },
                        { "type": "ip_cidr", "pattern": "10.0.0.0/8" }
                    ],
                    "action": "direct"
                }
            ],
            "default_action": "direct"
        }"#;

        assert!(set_custom_rules(json));

        // 1. Google 443 TCP -> Proxy
        let res1 = route_decision(Some("mail.google.com"), None, Some(443), Some("tcp"));
        assert_eq!(res1, RuleAction::Proxy);

        // 2. Google 80 TCP -> Fallback to Direct (because port!=443 and logic=AND)
        let res2 = route_decision(Some("mail.google.com"), None, Some(80), Some("tcp"));
        assert_eq!(res2, RuleAction::Direct);

        // 3. 192.168.1.100 -> Direct (r2 matches)
        let ip_lan: IpAddr = "192.168.1.100".parse().unwrap();
        let res3 = route_decision(None, Some(ip_lan), Some(8080), Some("tcp"));
        assert_eq!(res3, RuleAction::Direct);
    }

    #[test]
    fn test_cold_boot_domestic_fallback() {
        let _guard = TEST_LOCK.lock().unwrap();
        // 清空所有用户自定义规则，设默认动作为 proxy
        assert!(set_custom_rules(r#"{"rules":[], "default_action":"proxy"}"#));

        // 1. 国内常见域名 (如 baidu.com, qq.com, .cn) 应直接命中内置兜底 -> Direct
        assert_eq!(route_decision(Some("www.baidu.com"), None, Some(443), Some("tcp")), RuleAction::Direct);
        assert_eq!(route_decision(Some("api.bilibili.com"), None, Some(443), Some("tcp")), RuleAction::Direct);
        assert_eq!(route_decision(Some("gov.cn"), None, Some(80), Some("tcp")), RuleAction::Direct);

        // 2. 国内公网 IP (如 223.5.5.5, 114.114.114.114) 应命中内置 IP 兜底 -> Direct
        let ali_dns: IpAddr = "223.5.5.5".parse().unwrap();
        assert_eq!(route_decision(None, Some(ali_dns), Some(53), Some("udp")), RuleAction::Direct);

        // 3. 境外域名 (如 google.com) 无规则时回退至 default_action (Proxy)
        assert_eq!(route_decision(Some("google.com"), None, Some(443), Some("tcp")), RuleAction::Proxy);
    }

    #[test]
    fn test_fake_ip_routing_safety() {
        let _guard = TEST_LOCK.lock().unwrap();
        let json = r#"{
            "rules": [
                {
                    "id": "r_ads",
                    "name": "Ads Block",
                    "enabled": true,
                    "logic": "OR",
                    "conditions": [{ "type": "geosite", "pattern": "category-ads-all" }],
                    "action": "block"
                },
                {
                    "id": "r_priv",
                    "name": "Private Direct",
                    "enabled": true,
                    "logic": "OR",
                    "conditions": [
                        { "type": "ip_cidr", "pattern": "192.168.0.0/16" },
                        { "type": "geoip", "pattern": "private" }
                    ],
                    "action": "direct"
                },
                {
                    "id": "r_cn",
                    "name": "CN Direct",
                    "enabled": true,
                    "logic": "OR",
                    "conditions": [{ "type": "geosite", "pattern": "cn" }],
                    "action": "direct"
                },
                {
                    "id": "r_google",
                    "name": "Google Proxy",
                    "enabled": true,
                    "logic": "OR",
                    "conditions": [{ "type": "domain_suffix", "pattern": "google.com" }],
                    "action": "proxy"
                }
            ],
            "default_action": "proxy"
        }"#;

        assert!(set_custom_rules(json));

        let fake_ip: IpAddr = "198.18.0.3".parse().unwrap();
        assert!(is_fake_ip(fake_ip));

        // 1. Google connection through Fake-IP MUST NOT match geoip:private -> must be Proxy!
        let dec1 = route_decision(Some("www.google.com"), Some(fake_ip), Some(443), Some("tcp"));
        assert_eq!(dec1, RuleAction::Proxy, "Google over Fake-IP must be Proxy, not hijacked by geoip:private");

        // 2. Real private LAN IP (192.168.1.1) MUST match geoip:private / LAN rule -> Direct
        let lan_ip: IpAddr = "192.168.1.1".parse().unwrap();
        let dec2 = route_decision(None, Some(lan_ip), Some(80), Some("tcp"));
        assert_eq!(dec2, RuleAction::Direct);

        // 3. Domestic Baidu connection through Fake-IP MUST match CN rule / fallback -> Direct
        let dec3 = route_decision(Some("www.baidu.com"), Some(fake_ip), Some(443), Some("tcp"));
        assert_eq!(dec3, RuleAction::Direct);

        // 4. Foreign domain without explicit rule over Fake-IP MUST fall back to default_action (Proxy)
        let dec4 = route_decision(Some("twitter.com"), Some(fake_ip), Some(443), Some("tcp"));
        assert_eq!(dec4, RuleAction::Proxy);
    }

    #[test]
    fn geosite_cn_direct_does_not_hijack_foreign_domains() {
        // 复现 Play 更新慢根因: geosite:cn 数据误含 googleapis.com 等境外域名,
        // 若 cn→direct 直接生效会把 Google 服务直连到 DNS 污染的国内假 IP。
        // 规则: geosite:cn→direct (排前), geosite:google→proxy (排后)。
        set_custom_rules(&format!(
            r#"{{"default_action":"proxy","rules":[
                {{"id":"r1","name":"cn-direct","action":"direct","logic":"OR","conditions":[{{"type":"geosite","pattern":"cn"}}]}},
                {{"id":"r2","name":"google-proxy","action":"proxy","logic":"OR","conditions":[{{"type":"geosite","pattern":"google"}}]}}
            ]}}"#
        ));
        // 境外 Google 域名必须走 proxy (不被 cn 数据误劫持)
        assert_eq!(
            route_decision(Some("update.googleapis.com"), None, Some(53), Some("udp")),
            RuleAction::Proxy,
            "update.googleapis.com 必须走 proxy (geosite cn 误含境外域名时不得直连)"
        );
        assert_eq!(
            route_decision(Some("play.google.com"), None, Some(443), Some("tcp")),
            RuleAction::Proxy
        );
        // 真正国内域名仍应直连 (cn 数据对真实 CN 域名有效)
        assert_eq!(
            route_decision(Some("www.baidu.com"), None, Some(80), Some("tcp")),
            RuleAction::Direct,
            "baidu 仍应直连"
        );
        set_custom_rules(r#"{"rules":[],"default_action":"proxy"}"#);
    }

    #[test]
    fn test_is_cn_ip_accuracy_and_boundary_cases() {
        // 1. 经典国内公共 DNS 与真实国内 IP 必中 (包括旧版线性遍历曾漏判的 114.114.114.114)
        assert!(is_cn_ip("114.114.114.114".parse().unwrap()), "114.114.114.114 必须命中 CN IP");
        assert!(is_cn_ip("223.5.5.5".parse().unwrap()), "223.5.5.5 必须命中 CN IP");
        assert!(is_cn_ip("180.76.76.76".parse().unwrap()), "180.76.76.76 必须命中 CN IP");
        assert!(is_cn_ip("110.242.74.102".parse().unwrap()), "Baidu IP 必须命中 CN IP");

        // 2. 境外公共 IP 绝不命中
        assert!(!is_cn_ip("8.8.8.8".parse().unwrap()), "Google 8.8.8.8 绝不能误判为 CN");
        assert!(!is_cn_ip("1.1.1.1".parse().unwrap()), "Cloudflare 1.1.1.1 绝不能误判为 CN");
        assert!(!is_cn_ip("142.250.190.46".parse().unwrap()), "Google Web IP 绝不能误判为 CN");

        // 3. Fake-IP (198.18.0.0/15) 必须在入口短路拦截为 false，防止代理流量倒灌
        assert!(!is_cn_ip("198.18.0.1".parse().unwrap()));
        assert!(!is_cn_ip("198.18.254.254".parse().unwrap()));
        assert!(!is_cn_ip("198.19.255.255".parse().unwrap()));

        // 4. 边界地址
        assert!(!is_cn_ip("0.0.0.0".parse().unwrap()));
        assert!(!is_cn_ip("255.255.255.255".parse().unwrap()));
    }

    #[test]
    fn test_private_ip_and_lan_router_domain() {
        let _guard = TEST_LOCK.lock().unwrap();
        // 清空所有规则，默认动作为 proxy
        set_custom_rules(r#"{"rules":[],"default_action":"proxy"}"#);

        // 1. IPv4 私有网段
        assert!(is_private_ip("192.168.1.1".parse().unwrap()));
        assert!(is_private_ip("192.168.0.254".parse().unwrap()));
        assert!(is_private_ip("10.0.0.1".parse().unwrap()));
        assert!(is_private_ip("10.254.254.254".parse().unwrap()));
        assert!(is_private_ip("172.16.0.1".parse().unwrap()));
        assert!(is_private_ip("172.31.255.255".parse().unwrap()));
        assert!(is_private_ip("127.0.0.1".parse().unwrap()));
        assert!(is_private_ip("169.254.1.1".parse().unwrap()));
        assert!(is_private_ip("100.64.0.1".parse().unwrap()));
        assert!(is_private_ip("224.0.0.251".parse().unwrap()), "mDNS 组播必须为 private");
        assert!(is_private_ip("239.255.255.250".parse().unwrap()), "SSDP 组播必须为 private");
        assert!(is_private_ip("255.255.255.255".parse().unwrap()), "广播必须为 private");

        // 2. 公网 IP 绝不能为 private
        assert!(!is_private_ip("1.1.1.1".parse().unwrap()));
        assert!(!is_private_ip("8.8.8.8".parse().unwrap()));
        assert!(!is_private_ip("223.5.5.5".parse().unwrap()));
        assert!(!is_private_ip("172.32.0.1".parse().unwrap()));

        // 3. Fake-IP (198.18.0.0/15) 必须在入口短路为 false
        assert!(!is_private_ip("198.18.0.1".parse().unwrap()));
        assert!(!is_private_ip("198.19.255.255".parse().unwrap()));

        // 4. IPv6 私有与本地地址
        assert!(is_private_ip("::1".parse().unwrap()));
        assert!(is_private_ip("fe80::1".parse().unwrap()));
        assert!(is_private_ip("fc00::1".parse().unwrap()));
        assert!(is_private_ip("ff02::fb".parse().unwrap())); // IPv6 mDNS
        assert!(!is_private_ip("2400:3200::1".parse().unwrap())); // AliDNS IPv6
        assert!(!is_private_ip("2001:4860:4860::8888".parse().unwrap())); // Google DNS IPv6

        // 5. 局域网与路由器域名
        assert!(is_lan_or_router_domain("router.asus.com"));
        assert!(is_lan_or_router_domain("tplogin.cn"));
        assert!(is_lan_or_router_domain("miwifi.com"));
        assert!(is_lan_or_router_domain("my-nas.local"));
        assert!(is_lan_or_router_domain("openwrt.lan"));
        assert!(is_lan_or_router_domain("printer.home.arpa"));
        assert!(!is_lan_or_router_domain("google.com"));
        assert!(!is_lan_or_router_domain("baidu.com"));

        // 6. route_decision 强制直连决策验证
        assert_eq!(
            route_decision(None, Some("192.168.1.1".parse().unwrap()), Some(80), Some("tcp")),
            RuleAction::Direct,
            "局域网 IP 必须返回 Direct"
        );
        assert_eq!(
            route_decision(Some("router.asus.com"), None, Some(80), Some("tcp")),
            RuleAction::Direct,
            "路由器域名必须返回 Direct"
        );
        assert_eq!(
            route_decision(None, Some("224.0.0.251".parse().unwrap()), Some(5353), Some("udp")),
            RuleAction::Direct,
            "mDNS 组播必须返回 Direct"
        );

        // 7. GeoIP / GeoSite 特殊标签识别
        assert_eq!(
            route_decision(Some("my-server.local"), None, Some(80), Some("tcp")),
            RuleAction::Direct
        );
    }

    #[test]
    fn test_outbound_modes_switching_matrix() {
        let _guard = TEST_LOCK.lock().unwrap();
        // 初始化干净路由表 (默认 proxy 动作)
        set_custom_rules(r#"{"rules":[],"default_action":"proxy"}"#);

        // ── 测试 1: 规则模式 (Mode 0) ──
        set_outbound_mode(0);
        assert_eq!(get_outbound_mode(), 0);

        // 1.1 局域网 IP / 路由器域名 -> DIRECT
        let (action, rule) = route_decision_detailed(Some("router.asus.com"), None, Some(80), Some("tcp"));
        assert_eq!(action, RuleAction::Direct);
        assert!(rule.contains("Router"));

        let (action, rule) = route_decision_detailed(None, Some("192.168.1.1".parse().unwrap()), Some(80), Some("tcp"));
        assert_eq!(action, RuleAction::Direct);
        assert!(rule.contains("Private IP"));

        // 1.2 境外域名 / 境外 IP -> PROXY (默认回退)
        let (action, _) = route_decision_detailed(Some("www.google.com"), None, Some(443), Some("tcp"));
        assert_eq!(action, RuleAction::Proxy);

        let (action, _) = route_decision_detailed(None, Some("8.8.8.8".parse().unwrap()), Some(53), Some("udp"));
        assert_eq!(action, RuleAction::Proxy);

        // 1.3 国内域名 / 国内 IP -> DIRECT (内置兜底)
        let (action, rule) = route_decision_detailed(Some("www.bilibili.com"), None, Some(443), Some("tcp"));
        assert_eq!(action, RuleAction::Direct);
        assert!(rule.contains("CN Domain"));

        let (action, rule) = route_decision_detailed(None, Some("114.114.114.114".parse().unwrap()), Some(53), Some("udp"));
        assert_eq!(action, RuleAction::Direct);
        assert!(rule.contains("CN IP"));

        // ── 测试 2: 全局代理模式 (Mode 1) ──
        set_outbound_mode(1);
        assert_eq!(get_outbound_mode(), 1);

        // 2.1 局域网保护依然有效 -> DIRECT
        let (action, _) = route_decision_detailed(Some("tplogin.cn"), None, Some(80), Some("tcp"));
        assert_eq!(action, RuleAction::Direct, "全局代理下局域网路由器管理域名必须直连保护");

        let (action, _) = route_decision_detailed(None, Some("10.0.0.1".parse().unwrap()), Some(80), Some("tcp"));
        assert_eq!(action, RuleAction::Direct, "全局代理下局域网私有 IP 必须直连保护");

        // 2.2 境外域名与国内域名全部无差别走代理 -> PROXY
        let (action, rule) = route_decision_detailed(Some("www.google.com"), None, Some(443), Some("tcp"));
        assert_eq!(action, RuleAction::Proxy);
        assert!(rule.contains("Global Proxy Override"));

        let (action, rule) = route_decision_detailed(Some("www.bilibili.com"), None, Some(443), Some("tcp"));
        assert_eq!(action, RuleAction::Proxy);
        assert!(rule.contains("Global Proxy Override"));

        let (action, rule) = route_decision_detailed(None, Some("114.114.114.114".parse().unwrap()), Some(53), Some("udp"));
        assert_eq!(action, RuleAction::Proxy);
        assert!(rule.contains("Global Proxy Override"));

        // ── 测试 3: 全局直连模式 (Mode 2) ──
        set_outbound_mode(2);
        assert_eq!(get_outbound_mode(), 2);

        // 3.1 局域网 -> DIRECT
        let (action, _) = route_decision_detailed(None, Some("192.168.1.1".parse().unwrap()), Some(80), Some("tcp"));
        assert_eq!(action, RuleAction::Direct);

        // 3.2 境外域名与国内域名全部走直连 -> DIRECT
        let (action, rule) = route_decision_detailed(Some("www.google.com"), None, Some(443), Some("tcp"));
        assert_eq!(action, RuleAction::Direct);
        assert!(rule.contains("Global Direct Override"));

        let (action, rule) = route_decision_detailed(Some("www.bilibili.com"), None, Some(443), Some("tcp"));
        assert_eq!(action, RuleAction::Direct);
        assert!(rule.contains("Global Direct Override"));

        let (action, rule) = route_decision_detailed(None, Some("8.8.8.8".parse().unwrap()), Some(53), Some("udp"));
        assert_eq!(action, RuleAction::Direct);
        assert!(rule.contains("Global Direct Override"));

        // 复原回默认模式
        set_outbound_mode(0);
    }

    #[test]
    fn test_known_non_cn_domains_never_routed_to_direct_by_cn_rule() {
        let _guard = TEST_LOCK.lock().unwrap();
        set_custom_rules(r#"{"rules":[],"default_action":"proxy"}"#);
        set_outbound_mode(0);

        let non_cn_test_domains = [
            "update.googleapis.com",
            "play.googleapis.com",
            "youtubei.googleapis.com",
            "rr1---sn-xxx.googlevideo.com",
            "i.ytimg.com",
            "yt3.ggpht.com",
            "api.twitter.com",
            "t.co",
            "api.telegram.org",
            "api.spotify.com",
            "api.tiktokv.com",
            "api.openai.com",
            "api.anthropic.com",
            "claude.ai",
        ];

        for &dom in &non_cn_test_domains {
            assert!(
                is_known_non_cn_domain(dom),
                "{dom} 必须被识别为已知境外主流域名"
            );
            assert!(
                !is_cn_domain(dom),
                "{dom} 绝不能被 is_cn_domain 误判为国内域名"
            );
            assert!(
                !is_cn_domain_strict(dom),
                "{dom} 绝不能被 is_cn_domain_strict 误判为国内域名"
            );
            let (action, _) = route_decision_detailed(Some(dom), None, Some(443), Some("tcp"));
            assert_eq!(
                action,
                RuleAction::Proxy,
                "{dom} 在规则模式下必须命中 PROXY"
            );
        }
    }
}
