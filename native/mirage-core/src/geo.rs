//! Geo 数据文件解析与规则集匹配器 (geosite.dat / geoip.dat)。
//!
//! 零外部依赖解析标准 v2ray-rules-dat / Loyalsoldier / v2fly 格式的 protobuf 数据集。

use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr};
use std::path::Path;
use std::sync::{OnceLock, RwLock};
use tracing::{info, warn};

#[derive(Debug, Clone)]
pub enum SiteDomain {
    Keyword(String),
    Regex(String, Option<regex::Regex>),
    Suffix(String),
    Exact(String),
}

impl SiteDomain {
    pub fn matches(&self, domain: &str) -> bool {
        let d = domain.trim_end_matches('.').to_ascii_lowercase();
        match self {
            SiteDomain::Exact(v) => d == *v,
            SiteDomain::Suffix(v) => d == *v || d.ends_with(&format!(".{v}")),
            SiteDomain::Keyword(v) => d.contains(v),
            SiteDomain::Regex(_, Some(re)) => re.is_match(&d),
            SiteDomain::Regex(raw, None) => d.contains(raw),
        }
    }
}

#[derive(Debug, Clone)]
pub struct Ipv4Cidr {
    pub net: u32,
    pub mask: u32,
    pub prefix: u8,
}

impl Ipv4Cidr {
    pub fn new(ip: Ipv4Addr, prefix: u8) -> Self {
        let net = u32::from(ip);
        let mask = if prefix == 0 { 0 } else { !0u32 << (32 - prefix) };
        Self {
            net: net & mask,
            mask,
            prefix,
        }
    }

    pub fn contains(&self, ip: Ipv4Addr) -> bool {
        (u32::from(ip) & self.mask) == self.net
    }
}

#[derive(Debug, Clone)]
pub struct Ipv6Cidr {
    pub net: u128,
    pub mask: u128,
    pub prefix: u8,
}

impl Ipv6Cidr {
    pub fn new(ip: std::net::Ipv6Addr, prefix: u8) -> Self {
        let net = u128::from(ip);
        let mask = if prefix == 0 { 0 } else { !0u128 << (128 - prefix) };
        Self {
            net: net & mask,
            mask,
            prefix,
        }
    }

    pub fn contains(&self, ip: std::net::Ipv6Addr) -> bool {
        (u128::from(ip) & self.mask) == self.net
    }
}

#[derive(Debug, Clone)]
pub enum IpCidr {
    V4(Ipv4Cidr),
    V6(Ipv6Cidr),
}

/// GeoSite 快速哈希索引匹配器 (将 O(N) 线性搜索优化为 O(1) 哈希查询)
#[derive(Debug, Clone, Default)]
pub struct GeoSiteMatcher {
    pub count: usize,
    pub exact_set: std::collections::HashSet<String>,
    pub suffix_set: std::collections::HashSet<String>,
    pub other_rules: Vec<SiteDomain>,
}

impl GeoSiteMatcher {
    pub fn new(domains: Vec<SiteDomain>) -> Self {
        let count = domains.len();
        let mut exact_set = std::collections::HashSet::with_capacity(domains.len());
        let mut suffix_set = std::collections::HashSet::with_capacity(domains.len());
        let mut other_rules = Vec::new();

        for d in domains {
            match d {
                SiteDomain::Exact(v) => {
                    exact_set.insert(v.trim_end_matches('.').to_ascii_lowercase());
                }
                SiteDomain::Suffix(v) => {
                    suffix_set.insert(v.trim_end_matches('.').to_ascii_lowercase());
                }
                other => other_rules.push(other),
            }
        }
        Self {
            count,
            exact_set,
            suffix_set,
            other_rules,
        }
    }

    pub fn matches(&self, domain: &str) -> bool {
        let d = domain.trim_end_matches('.').to_ascii_lowercase();
        if self.exact_set.contains(&d) {
            return true;
        }
        if self.suffix_set.contains(&d) {
            return true;
        }
        // 快速遍历子域后缀 (例如 "sub.example.com" 遍历 "example.com" 和 "com")
        let mut remaining = d.as_str();
        while let Some(idx) = remaining.find('.') {
            remaining = &remaining[idx + 1..];
            if self.suffix_set.contains(remaining) {
                return true;
            }
        }
        for item in &self.other_rules {
            if item.matches(domain) {
                return true;
            }
        }
        false
    }
}

#[derive(Default)]
pub struct GeoStore {
    /// geosite: tag (大写, 如 "CN", "GOOGLE", "CATEGORY-ADS-ALL") -> 哈希索引匹配器
    pub sites: HashMap<String, GeoSiteMatcher>,
    /// geoip: code (大写, 如 "CN", "TELEGRAM", "PRIVATE") -> IPv4 网段列表
    pub ip_v4: HashMap<String, Vec<Ipv4Cidr>>,
    /// geoip: code (大写, 如 "CN", "TELEGRAM", "PRIVATE") -> IPv6 网段列表
    pub ip_v6: HashMap<String, Vec<Ipv6Cidr>>,
    pub geosite_path: String,
    pub geoip_path: String,
}

impl GeoStore {
    pub fn match_geosite(&self, tag: &str, domain: &str) -> bool {
        let tag_upper = tag.trim().to_ascii_uppercase();
        if let Some(matcher) = self.sites.get(&tag_upper) {
            if matcher.matches(domain) {
                return true;
            }
        }
        if tag_upper == "PRIVATE" || tag_upper == "LAN" {
            return crate::direct::is_lan_or_router_domain(domain);
        }
        false
    }

    pub fn match_geoip(&self, code: &str, ip: IpAddr) -> bool {
        if crate::direct::is_fake_ip(ip) {
            return false;
        }
        let code_upper = code.trim().to_ascii_uppercase();
        if code_upper == "PRIVATE" || code_upper == "LAN" {
            return crate::direct::is_private_ip(ip);
        }
        match ip {
            IpAddr::V4(v4) => {
                if let Some(list) = self.ip_v4.get(&code_upper) {
                    for cidr in list {
                        if cidr.contains(v4) {
                            return true;
                        }
                    }
                }
            }
            IpAddr::V6(v6) => {
                if let Some(list) = self.ip_v6.get(&code_upper) {
                    for cidr in list {
                        if cidr.contains(v6) {
                            return true;
                        }
                    }
                }
            }
        }
        false
    }
}

fn global_geo() -> &'static RwLock<GeoStore> {
    static G: OnceLock<RwLock<GeoStore>> = OnceLock::new();
    G.get_or_init(|| RwLock::new(GeoStore::default()))
}

// ── 简易 Protobuf Wire 解码器 ────────────────────────────────────────────────

fn read_varint(buf: &[u8], mut pos: usize) -> Option<(u64, usize)> {
    let mut result = 0u64;
    let mut shift = 0;
    while pos < buf.len() {
        let b = buf[pos];
        pos += 1;
        result |= ((b & 0x7F) as u64) << shift;
        if (b & 0x80) == 0 {
            return Some((result, pos));
        }
        shift += 7;
        if shift >= 64 {
            return None;
        }
    }
    None
}

fn read_len_delim<'a>(buf: &'a [u8], pos: usize) -> Option<(&'a [u8], usize)> {
    let (len, pos) = read_varint(buf, pos)?;
    let len = len as usize;
    if pos + len > buf.len() {
        return None;
    }
    Some((&buf[pos..pos + len], pos + len))
}

// ── geosite.dat 解析 ─────────────────────────────────────────────────────────

fn parse_domain_msg(buf: &[u8]) -> Option<SiteDomain> {
    let mut pos = 0;
    let mut dtype = 0u64;
    let mut value = String::new();

    while pos < buf.len() {
        let (tag, next_pos) = read_varint(buf, pos)?;
        pos = next_pos;
        let fn_num = tag >> 3;
        let wire_type = tag & 7;

        match (fn_num, wire_type) {
            (1, 0) => {
                // type
                let (v, next_pos) = read_varint(buf, pos)?;
                dtype = v;
                pos = next_pos;
            }
            (2, 2) => {
                // value
                let (data, next_pos) = read_len_delim(buf, pos)?;
                value = String::from_utf8_lossy(data).trim_end_matches('.').to_ascii_lowercase();
                pos = next_pos;
            }
            (_, 0) => {
                let (_, next_pos) = read_varint(buf, pos)?;
                pos = next_pos;
            }
            (_, 2) => {
                let (_, next_pos) = read_len_delim(buf, pos)?;
                pos = next_pos;
            }
            (_, 1) => {
                if pos + 8 > buf.len() { break; }
                pos += 8;
            }
            (_, 5) => {
                if pos + 4 > buf.len() { break; }
                pos += 4;
            }
            _ => break,
        }
    }

    if value.is_empty() {
        return None;
    }

    match dtype {
        0 => Some(SiteDomain::Keyword(value)),
        1 => {
            let compiled = regex::Regex::new(&value).ok();
            Some(SiteDomain::Regex(value, compiled))
        }
        2 => Some(SiteDomain::Suffix(value)),
        3 => Some(SiteDomain::Exact(value)),
        _ => Some(SiteDomain::Suffix(value)),
    }
}

fn parse_geosite_entry(buf: &[u8]) -> Option<(String, Vec<SiteDomain>)> {
    let mut pos = 0;
    let mut code = String::new();
    let mut domains = Vec::new();

    while pos < buf.len() {
        let (tag, next_pos) = read_varint(buf, pos)?;
        pos = next_pos;
        let fn_num = tag >> 3;
        let wire_type = tag & 7;

        match (fn_num, wire_type) {
            (1, 2) => {
                // country_code
                let (data, next_pos) = read_len_delim(buf, pos)?;
                code = String::from_utf8_lossy(data).trim().to_ascii_uppercase();
                pos = next_pos;
            }
            (2, 2) => {
                // domain message
                let (data, next_pos) = read_len_delim(buf, pos)?;
                if let Some(d) = parse_domain_msg(data) {
                    domains.push(d);
                }
                pos = next_pos;
            }
            (_, 0) => {
                let (_, next_pos) = read_varint(buf, pos)?;
                pos = next_pos;
            }
            (_, 2) => {
                let (_, next_pos) = read_len_delim(buf, pos)?;
                pos = next_pos;
            }
            (_, 1) => {
                if pos + 8 > buf.len() { break; }
                pos += 8;
            }
            (_, 5) => {
                if pos + 4 > buf.len() { break; }
                pos += 4;
            }
            _ => break,
        }
    }

    if code.is_empty() {
        None
    } else {
        Some((code, domains))
    }
}

pub fn parse_geosite_file(data: &[u8]) -> HashMap<String, Vec<SiteDomain>> {
    let mut map = HashMap::new();
    let mut pos = 0;
    while pos < data.len() {
        let Some((tag, next_pos)) = read_varint(data, pos) else { break };
        pos = next_pos;
        let fn_num = tag >> 3;
        let wire_type = tag & 7;

        if fn_num == 1 && wire_type == 2 {
            let Some((entry_data, next_pos)) = read_len_delim(data, pos) else { break };
            if let Some((code, domains)) = parse_geosite_entry(entry_data) {
                map.insert(code, domains);
            }
            pos = next_pos;
        } else if wire_type == 0 {
            let Some((_, next_pos)) = read_varint(data, pos) else { break };
            pos = next_pos;
        } else if wire_type == 2 {
            let Some((_, next_pos)) = read_len_delim(data, pos) else { break };
            pos = next_pos;
        } else if wire_type == 1 {
            if pos + 8 > data.len() { break; }
            pos += 8;
        } else if wire_type == 5 {
            if pos + 4 > data.len() { break; }
            pos += 4;
        } else {
            break;
        }
    }
    map
}

// ── geoip.dat 解析 ───────────────────────────────────────────────────────────

fn parse_cidr_msg(buf: &[u8]) -> Option<IpCidr> {
    let mut pos = 0;
    let mut ip_bytes = Vec::new();
    let mut prefix = 0u8;

    while pos < buf.len() {
        let (tag, next_pos) = read_varint(buf, pos)?;
        pos = next_pos;
        let fn_num = tag >> 3;
        let wire_type = tag & 7;

        match (fn_num, wire_type) {
            (1, 2) => {
                let (data, next_pos) = read_len_delim(buf, pos)?;
                ip_bytes = data.to_vec();
                pos = next_pos;
            }
            (2, 0) => {
                let (v, next_pos) = read_varint(buf, pos)?;
                prefix = (v as u8).min(128);
                pos = next_pos;
            }
            (_, 0) => {
                let (_, next_pos) = read_varint(buf, pos)?;
                pos = next_pos;
            }
            (_, 2) => {
                let (_, next_pos) = read_len_delim(buf, pos)?;
                pos = next_pos;
            }
            (_, 1) => {
                if pos + 8 > buf.len() { break; }
                pos += 8;
            }
            (_, 5) => {
                if pos + 4 > buf.len() { break; }
                pos += 4;
            }
            _ => break,
        }
    }

    if ip_bytes.len() == 4 {
        let ip = Ipv4Addr::new(ip_bytes[0], ip_bytes[1], ip_bytes[2], ip_bytes[3]);
        Some(IpCidr::V4(Ipv4Cidr::new(ip, prefix.min(32))))
    } else if ip_bytes.len() == 16 {
        let mut octets = [0u8; 16];
        octets.copy_from_slice(&ip_bytes[..16]);
        let ip = std::net::Ipv6Addr::from(octets);
        Some(IpCidr::V6(Ipv6Cidr::new(ip, prefix.min(128))))
    } else {
        None
    }
}

fn parse_geoip_entry(buf: &[u8]) -> Option<(String, Vec<Ipv4Cidr>, Vec<Ipv6Cidr>)> {
    let mut pos = 0;
    let mut code = String::new();
    let mut v4_cidrs = Vec::new();
    let mut v6_cidrs = Vec::new();

    while pos < buf.len() {
        let (tag, next_pos) = read_varint(buf, pos)?;
        pos = next_pos;
        let fn_num = tag >> 3;
        let wire_type = tag & 7;

        match (fn_num, wire_type) {
            (1, 2) => {
                let (data, next_pos) = read_len_delim(buf, pos)?;
                code = String::from_utf8_lossy(data).trim().to_ascii_uppercase();
                pos = next_pos;
            }
            (2, 2) => {
                let (data, next_pos) = read_len_delim(buf, pos)?;
                if let Some(c) = parse_cidr_msg(data) {
                    match c {
                        IpCidr::V4(v4) => v4_cidrs.push(v4),
                        IpCidr::V6(v6) => v6_cidrs.push(v6),
                    }
                }
                pos = next_pos;
            }
            (_, 0) => {
                let (_, next_pos) = read_varint(buf, pos)?;
                pos = next_pos;
            }
            (_, 2) => {
                let (_, next_pos) = read_len_delim(buf, pos)?;
                pos = next_pos;
            }
            (_, 1) => {
                if pos + 8 > buf.len() { break; }
                pos += 8;
            }
            (_, 5) => {
                if pos + 4 > buf.len() { break; }
                pos += 4;
            }
            _ => break,
        }
    }

    if code.is_empty() {
        None
    } else {
        Some((code, v4_cidrs, v6_cidrs))
    }
}

pub fn parse_geoip_file(data: &[u8]) -> (HashMap<String, Vec<Ipv4Cidr>>, HashMap<String, Vec<Ipv6Cidr>>) {
    let mut v4_map = HashMap::new();
    let mut v6_map = HashMap::new();
    let mut pos = 0;
    while pos < data.len() {
        let Some((tag, next_pos)) = read_varint(data, pos) else { break };
        pos = next_pos;
        let fn_num = tag >> 3;
        let wire_type = tag & 7;

        if fn_num == 1 && wire_type == 2 {
            let Some((entry_data, next_pos)) = read_len_delim(data, pos) else { break };
            if let Some((code, v4, v6)) = parse_geoip_entry(entry_data) {
                if !v4.is_empty() {
                    v4_map.insert(code.clone(), v4);
                }
                if !v6.is_empty() {
                    v6_map.insert(code, v6);
                }
            }
            pos = next_pos;
        } else if wire_type == 0 {
            let Some((_, next_pos)) = read_varint(data, pos) else { break };
            pos = next_pos;
        } else if wire_type == 2 {
            let Some((_, next_pos)) = read_len_delim(data, pos) else { break };
            pos = next_pos;
        } else if wire_type == 1 {
            if pos + 8 > data.len() { break; }
            pos += 8;
        } else if wire_type == 5 {
            if pos + 4 > data.len() { break; }
            pos += 4;
        } else {
            break;
        }
    }
    (v4_map, v6_map)
}

// ── 公共加载与匹配接口 ───────────────────────────────────────────────────────

/// 加载外部 Geo 文件 (geosite.dat 与 geoip.dat)
pub fn load_geo_files(geosite_path: &str, geoip_path: &str) -> (usize, usize) {
    let mut site_map = HashMap::new();
    let mut ip_v4_map = HashMap::new();
    let mut ip_v6_map = HashMap::new();

    if !geosite_path.is_empty() && Path::new(geosite_path).exists() {
        match std::fs::read(geosite_path) {
            Ok(bytes) => {
                site_map = parse_geosite_file(&bytes);
                info!("[GEO] 成功加载 geosite.dat ({} tags, 路径: {})", site_map.len(), geosite_path);
            }
            Err(e) => warn!("[GEO] 读取 geosite.dat 失败 ({}): {}", geosite_path, e),
        }
    }

    if !geoip_path.is_empty() && Path::new(geoip_path).exists() {
        match std::fs::read(geoip_path) {
            Ok(bytes) => {
                let res = parse_geoip_file(&bytes);
                ip_v4_map = res.0;
                ip_v6_map = res.1;
                info!(
                    "[GEO] 成功加载 geoip.dat ({} IPv4 codes, {} IPv6 codes, 路径: {})",
                    ip_v4_map.len(), ip_v6_map.len(), geoip_path
                );
            }
            Err(e) => warn!("[GEO] 读取 geoip.dat 失败 ({}): {}", geoip_path, e),
        }
    }

    let site_count = site_map.len();
    let ip_count = ip_v4_map.len() + ip_v6_map.len();

    let indexed_sites: HashMap<String, GeoSiteMatcher> = site_map
        .into_iter()
        .map(|(tag, domains)| (tag, GeoSiteMatcher::new(domains)))
        .collect();

    let mut g = global_geo().write().unwrap_or_else(|e| e.into_inner());
    g.sites = indexed_sites;
    g.ip_v4 = ip_v4_map;
    g.ip_v6 = ip_v6_map;
    g.geosite_path = geosite_path.to_string();
    g.geoip_path = geoip_path.to_string();

    (site_count, ip_count)
}

/// 匹配域名是否命中指定 geosite tag
pub fn match_geosite_tag(tag: &str, domain: &str) -> bool {
    let g = global_geo().read().unwrap_or_else(|e| e.into_inner());
    g.match_geosite(tag, domain)
}

/// 匹配 IP 是否命中指定 geoip code
pub fn match_geoip_code(code: &str, ip: IpAddr) -> bool {
    let g = global_geo().read().unwrap_or_else(|e| e.into_inner());
    g.match_geoip(code, ip)
}

/// 获取当前已加载的 GeoSite tags 和 GeoIP codes 列表 (按字母排序 JSON)
pub fn get_geo_tags_json() -> String {
    let g = global_geo().read().unwrap_or_else(|e| e.into_inner());
    let mut sites: Vec<String> = g.sites.keys().cloned().collect();
    let mut ips: Vec<String> = g.ip_v4.keys().cloned().collect();
    sites.sort();
    ips.sort();

    serde_json::json!({
        "geosite_count": sites.len(),
        "geoip_count": ips.len(),
        "geosite_tags": sites,
        "geoip_codes": ips,
    }).to_string()
}

/// 获取包含详细条目数量的 GeoSite 和 GeoIP 列表 (供 Tag 内省搜索器使用)
pub fn get_geo_tags_detail_json() -> String {
    let g = global_geo().read().unwrap_or_else(|e| e.into_inner());
    
    let mut site_details: Vec<serde_json::Value> = g.sites.iter().map(|(tag, matcher)| {
        serde_json::json!({
            "tag": tag,
            "count": matcher.count,
        })
    }).collect();
    site_details.sort_by(|a, b| {
        a["tag"].as_str().unwrap_or("").cmp(b["tag"].as_str().unwrap_or(""))
    });

    let mut ip_details: Vec<serde_json::Value> = g.ip_v4.iter().map(|(code, entries)| {
        serde_json::json!({
            "code": code,
            "count": entries.len(),
        })
    }).collect();
    ip_details.sort_by(|a, b| {
        a["code"].as_str().unwrap_or("").cmp(b["code"].as_str().unwrap_or(""))
    });

    serde_json::json!({
        "geosite_count": site_details.len(),
        "geoip_count": ip_details.len(),
        "geosite_tags": site_details,
        "geoip_codes": ip_details,
        "geosite_path": g.geosite_path,
        "geoip_path": g.geoip_path,
    }).to_string()
}
