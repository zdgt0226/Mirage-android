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

/// 判断 IP 是否属于中国段 (优先查动态加载的 GeoIP: CN，未就绪时二分查内置 CN IP 段)
pub fn is_cn_ip(ip: IpAddr) -> bool {
    if is_fake_ip(ip) {
        return false;
    }
    if match_geoip_code("CN", ip) {
        return true;
    }
    if let IpAddr::V4(v4) = ip {
        let ip_u32 = u32::from(v4);
        for &(net, prefix) in crate::direct_cn_ipv4::CN_IPV4 {
            let mask = if prefix == 0 {
                0
            } else {
                !((1u32 << (32 - prefix)) - 1)
            };
            if (ip_u32 & mask) == net {
                return true;
            }
        }
    }
    false
}

/// 判断域名是否属于中国段 (优先查动态加载的 GeoSite: CN，未就绪时查内置 CN 常用域名表及 .cn 后缀)
pub fn is_cn_domain(domain: &str) -> bool {
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

/// 综合决策请求的目标动作 (支持传入 域名、IP、端口、协议)
pub fn route_decision(
    domain: Option<&str>,
    ip: Option<IpAddr>,
    port: Option<u16>,
    protocol: Option<&str>,
) -> RuleAction {
    let r = router_store().read().unwrap_or_else(|e| e.into_inner());

    // 1. 优先遍历用户自定义复合规则 (自上而下，首条命中即返回)
    for rule in &r.rules {
        if rule.matches(domain, ip, port, protocol) {
            record_rule_hit(&rule.id, &rule.name, rule.action.as_str());
            return rule.action;
        }
    }

    // 2. 检查直连 IP 标记 (DNS 阶段标记的直连真实 IP)
    if let Some(ip_addr) = ip {
        if is_direct_ip(ip_addr) {
            return RuleAction::Direct;
        }
    }

    // 3. 国内流量智能直连兜底 (在用户未配置规则/冷启动状态下，确保国内域名/IP 默认直连)
    if let Some(dom) = domain {
        if is_cn_domain(dom) {
            return RuleAction::Direct;
        }
    }
    if let Some(ip_addr) = ip {
        if is_cn_ip(ip_addr) {
            return RuleAction::Direct;
        }
    }

    // 4. 回退至默认动作 (境外未命中规则默认走 proxy)
    r.default_action
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

/// 设置自定义规则 (兼容传统单条件规则 + 全新多条件复合规则 JSON)
pub fn set_custom_rules(json: &str) -> bool {
    let parsed: Result<serde_json::Value, _> = serde_json::from_str(json);
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

    #[test]
    fn test_composite_rule_and_or() {
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
}
