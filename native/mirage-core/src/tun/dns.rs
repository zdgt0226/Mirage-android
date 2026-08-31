//! DNS 应答器 + 国内外分流。
//!
//! TUN 内的 `dns_addr:53` 查询 (UDP/TCP) 处理:
//! - **国内域名** (见 `crate::direct::is_cn_domain`) → 异步向上游 DNS (223.5.5.5, protect
//!   UDP socket 走真实网络) 查询**真实 IP** → 应答 A 记录 → 客户端连真实 IP → 命中 CN 段 → 直连
//! - **国外域名** → 分配 fake-IP (198.18.0.0/16) → 客户端连 fake-IP → 隧道 (服务端远程解析)
//! - **AAAA** → 空 answer (NOERROR), 强制回落 IPv4
//!
//! 上游查询结果缓存 300s, `direct::resolve_direct_domain` 可同步读取兜底。

use smoltcp::iface::SocketHandle;
use std::collections::HashMap;
use std::sync::{Arc, Mutex as StdMutex, OnceLock};
use std::time::{Duration, Instant};
use tracing::debug;

use crate::engine::Engine;
use crate::tun::TunStack;
use crate::tun::tcp::TunTcpStream;

/// 国内域名 → 真实 IP 共享缓存 (分流写入, direct::resolve_direct_domain 读)。
fn direct_cache() -> &'static StdMutex<HashMap<String, (std::net::Ipv4Addr, Instant)>> {
    static C: OnceLock<StdMutex<HashMap<String, (std::net::Ipv4Addr, Instant)>>> = OnceLock::new();
    C.get_or_init(|| StdMutex::new(HashMap::new()))
}

/// 国内直连 DNS 服务器地址 (默认 223.5.5.5)
fn direct_dns_server() -> &'static StdMutex<std::net::Ipv4Addr> {
    static S: OnceLock<StdMutex<std::net::Ipv4Addr>> = OnceLock::new();
    S.get_or_init(|| StdMutex::new(std::net::Ipv4Addr::new(223, 5, 5, 5)))
}

/// 国外/远程 DNS 服务器地址 (默认 1.1.1.1)
fn remote_dns_server() -> &'static StdMutex<std::net::IpAddr> {
    static S: OnceLock<StdMutex<std::net::IpAddr>> = OnceLock::new();
    S.get_or_init(|| StdMutex::new(std::net::IpAddr::V4(std::net::Ipv4Addr::new(1, 1, 1, 1))))
}

pub const DNS_RESPONSE_TTL: u32 = 60;
const CACHE_TTL: Duration = Duration::from_secs(300);

/// 设置国内直连 DNS
pub fn set_direct_dns(ip: std::net::Ipv4Addr) {
    let mut s = direct_dns_server().lock().unwrap_or_else(|e| e.into_inner());
    *s = ip;
    clear_direct_cache();
    tracing::info!("[TUN-DNS] 国内直连 DNS 设置为: {}", ip);
}

pub fn get_direct_dns() -> std::net::Ipv4Addr {
    *direct_dns_server().lock().unwrap_or_else(|e| e.into_inner())
}

/// 设置国外远程 DNS
pub fn set_remote_dns(ip: std::net::IpAddr) {
    let mut s = remote_dns_server().lock().unwrap_or_else(|e| e.into_inner());
    *s = ip;
    tracing::info!("[TUN-DNS] 国外远程 DNS 设置为: {}", ip);
}

pub fn get_remote_dns() -> std::net::IpAddr {
    *remote_dns_server().lock().unwrap_or_else(|e| e.into_inner())
}

/// 清空直连 DNS 缓存 (VPN 重连/断开时调用)
pub fn clear_direct_cache() {
    let mut map = direct_cache().lock().unwrap_or_else(|e| e.into_inner());
    map.clear();
    tracing::info!("[TUN-DNS] 直连 DNS 缓存已清空");
}

const DIRECT_CACHE_MAX: usize = 1024;

/// 安全写入直连 DNS 缓存 (带容量上限与过期淘汰, 防 OOM)
fn insert_direct_cache(domain: String, ip: std::net::Ipv4Addr) {
    let mut map = direct_cache().lock().unwrap_or_else(|e| e.into_inner());
    if map.len() >= DIRECT_CACHE_MAX {
        let now = Instant::now();
        map.retain(|_, (_, at)| now.duration_since(*at) < CACHE_TTL);
        if map.len() >= DIRECT_CACHE_MAX {
            if let Some(k) = map.keys().next().cloned() {
                map.remove(&k);
            }
        }
    }
    map.insert(domain, (ip, Instant::now()));
}

/// 读直连 DNS 缓存 (同步, 兜底用)。
pub fn direct_dns_lookup(domain: &str) -> Option<std::net::IpAddr> {
    let map = direct_cache().lock().unwrap_or_else(|e| e.into_inner());
    let (ip, at) = map.get(domain)?;
    if at.elapsed() < CACHE_TTL {
        Some(std::net::IpAddr::V4(*ip))
    } else {
        None
    }
}

/// 构造标准 A 查询 (无 EDNS)。
fn build_a_query(domain: &str, id: u16) -> Vec<u8> {
    let mut q = Vec::with_capacity(40);
    q.extend_from_slice(&id.to_be_bytes());
    q.extend_from_slice(&[0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0]); // RD, 1 question
    for label in domain.trim_end_matches('.').split('.') {
        if label.is_empty() {
            continue;
        }
        q.push(label.len() as u8);
        q.extend_from_slice(label.as_bytes());
    }
    q.push(0);
    q.extend_from_slice(&[0, 1, 0, 1]); // A, IN
    q
}

/// 遵循 RFC 1035 跳过 DNS 名字 (支持完整指针 0xC0、标签、及混合压缩格式)。
fn skip_dns_name(buf: &[u8], mut offset: usize) -> Option<usize> {
    let mut steps = 0;
    while offset < buf.len() {
        if steps > 128 {
            return None; // 环路防御
        }
        steps += 1;
        let len = buf[offset] as usize;
        if len == 0 {
            return Some(offset + 1);
        }
        if len & 0xC0 == 0xC0 {
            if offset + 2 > buf.len() {
                return None;
            }
            return Some(offset + 2);
        }
        if offset + 1 + len > buf.len() {
            return None;
        }
        offset += 1 + len;
    }
    None
}

/// 解析上游应答, 遍历并提取首个有效 A 记录 (完美支持 CNAME 链与压缩指针)。
fn parse_a_answer(buf: &[u8], qname_len: usize) -> Option<[u8; 4]> {
    if buf.len() < 12 {
        return None;
    }
    let ancount = u16::from_be_bytes([buf[6], buf[7]]);
    if ancount == 0 {
        return None;
    }
    let mut off = 12 + qname_len + 4;
    for _ in 0..ancount.min(16) {
        let r = skip_dns_name(buf, off)?;
        if r + 10 > buf.len() {
            return None;
        }
        let rtype = u16::from_be_bytes([buf[r], buf[r + 1]]);
        let rdlen = u16::from_be_bytes([buf[r + 8], buf[r + 9]]) as usize;
        let rd = r + 10;
        if rd + rdlen > buf.len() {
            return None;
        }
        if rtype == 1 && rdlen == 4 {
            return Some([buf[rd], buf[rd + 1], buf[rd + 2], buf[rd + 3]]);
        }
        off = rd + rdlen;
    }
    None
}

/// 异步向上游查询真实 IP (采用双上游并发竞速解析与极速兜底)。
pub async fn resolve_upstream(domain: &str) -> Option<std::net::Ipv4Addr> {
    {
        let map = direct_cache().lock().unwrap_or_else(|e| e.into_inner());
        if let Some((ip, at)) = map.get(domain) {
            if at.elapsed() < CACHE_TTL {
                tracing::info!("[TUN-DNS] 命中直连 DNS 缓存: {} → {}", domain, ip);
                return Some(*ip);
            }
        }
    }
    // 防 FD 耗尽: 限制同时进行的上游 DNS UDP Socket 并发总数不超过 32 个 (适度放宽以容纳冷启动并发潮)
    static DNS_UPSTREAM_SEM: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(32);
    let _permit = match tokio::time::timeout(Duration::from_millis(600), DNS_UPSTREAM_SEM.acquire()).await {
        Ok(Ok(p)) => p,
        _ => {
            tracing::warn!("[TUN-DNS] 上游直连解析并发达到上限 (32 并发)，触发快速降级");
            return None;
        }
    };

    let sock = match tokio::net::UdpSocket::bind("0.0.0.0:0").await {
        Ok(s) => s,
        Err(e) => {
            tracing::warn!("[TUN-DNS] 上游 UDP socket 创建失败: {e}");
            return None;
        }
    };
    // protect: 走真实网络 (否则被 TUN 卷回)
    crate::protect::protect(std::os::unix::io::AsRawFd::as_raw_fd(&sock));
    let id = fastrand::u16(..);
    let query = build_a_query(domain, id);
    let primary_dns = get_direct_dns();
    let fallback_ip = if primary_dns != std::net::Ipv4Addr::new(223, 5, 5, 5) {
        std::net::Ipv4Addr::new(223, 5, 5, 5)
    } else {
        std::net::Ipv4Addr::new(119, 29, 29, 29)
    };

    let up1 = std::net::SocketAddr::from((primary_dns, 53));
    let up2 = std::net::SocketAddr::from((fallback_ip, 53));

    // 并发向主上游和备用上游发送请求 (竞速消除 DNS 解析抖动与卡顿)
    let _ = sock.send_to(&query, up1).await;
    let _ = sock.send_to(&query, up2).await;

    let mut buf = [0u8; 1024];
    let start_time = std::time::Instant::now();
    match tokio::time::timeout(Duration::from_millis(1000), sock.recv_from(&mut buf)).await {
        Ok(Ok((v, from))) => {
            // S2 安全加固: 严格校验回包来源与随机化 ID，杜绝局域网恶意注入欺骗
            if (from == up1 || from == up2) && v >= 2 && u16::from_be_bytes([buf[0], buf[1]]) == id {
                let qname_end = skip_dns_name(&buf[..v], 12).map(|end| end - 12);
                if let Some(qlen) = qname_end {
                    if let Some(ip) = parse_a_answer(&buf[..v], qlen) {
                        let direct_v4 = std::net::Ipv4Addr::from(ip);
                        insert_direct_cache(domain.to_string(), direct_v4);
                        crate::direct::mark_direct_ip(std::net::IpAddr::V4(direct_v4));
                        tracing::info!(
                            "[TUN-DNS] 上游直连解析成功: {} → {} (耗时: {}ms, 上游: {})",
                            domain, direct_v4, start_time.elapsed().as_millis(), from
                        );
                        return Some(direct_v4);
                    }
                }
            }
            // 若第一个包 ID 不匹配或无 A 记录，尝试再读一个备选包
            match tokio::time::timeout(Duration::from_millis(500), sock.recv_from(&mut buf)).await {
                Ok(Ok((v2, from2))) if (from2 == up1 || from2 == up2) && v2 >= 2 && u16::from_be_bytes([buf[0], buf[1]]) == id => {
                    let qname_end = skip_dns_name(&buf[..v2], 12).map(|end| end - 12);
                    if let Some(qlen) = qname_end {
                        if let Some(ip) = parse_a_answer(&buf[..v2], qlen) {
                            let direct_v4 = std::net::Ipv4Addr::from(ip);
                            insert_direct_cache(domain.to_string(), direct_v4);
                            crate::direct::mark_direct_ip(std::net::IpAddr::V4(direct_v4));
                            tracing::info!(
                                "[TUN-DNS] 上游直连解析成功 (备选包): {} → {} (耗时: {}ms, 上游: {})",
                                domain, direct_v4, start_time.elapsed().as_millis(), from2
                            );
                            return Some(direct_v4);
                        }
                    }
                    None
                }
                _ => None,
            }
        }
        _ => {
            tracing::warn!(
                "[TUN-DNS] 上游直连解析超时 ({}ms): {} (主: {}, 备: {})",
                start_time.elapsed().as_millis(), domain, primary_dns, fallback_ip
            );
            None
        }
    }
}

/// 构造应答: A → 给定 IP; AAAA/其他 → 空 answer (NOERROR); rcode=3 → SERVFAIL
/// (隧道也不可用时的快速失败, 避免客户端连必死的 fake-IP 白等)。
fn build_response(query: &[u8], domain: &str, qtype: u16, a_record: Option<[u8; 4]>, question_len: usize, rcode: u8) -> Option<Vec<u8>> {
    if query.len() < 12 || question_len < 13 || question_len > query.len() {
        return None;
    }
    let id = &query[0..2];
    let question = &query[12..question_len];

    let mut resp = Vec::with_capacity(12 + question.len() + 16);
    resp.extend_from_slice(id);
    resp.extend_from_slice(&[0x81, 0x80 | (rcode & 0x0F)]);
    resp.extend_from_slice(&[0, 1]);
    if a_record.is_some() {
        resp.extend_from_slice(&[0, 1]);
    } else {
        resp.extend_from_slice(&[0, 0]);
    }
    resp.extend_from_slice(&[0, 0, 0, 0]);
    resp.extend_from_slice(question);

    if let Some(ip) = a_record {
        resp.extend_from_slice(&[0xC0, 0x0C]);
        resp.extend_from_slice(&qtype.to_be_bytes());
        resp.extend_from_slice(&[0, 1]); // IN
        // 统一使用 60s TTL，降低客户端 DNS 轮询负载与耗电；重连/启停时由 FakeIpMapper 重置机制兜底
        resp.extend_from_slice(&DNS_RESPONSE_TTL.to_be_bytes()); // TTL = 60s
        resp.extend_from_slice(&[0, 4]);
        resp.extend_from_slice(&ip);
    }
    let _ = domain;
    Some(resp)
}

/// 解析 DNS 查询, 返回 (QNAME, QTYPE, question 段长度)。
fn parse_query(buf: &[u8]) -> Option<(String, u16, usize)> {
    if buf.len() < 12 {
        return None;
    }
    let qdcount = u16::from_be_bytes([buf[4], buf[5]]);
    if qdcount < 1 {
        return None;
    }
    let mut off = 12usize;
    let mut labels = Vec::new();
    loop {
        let len = *buf.get(off)? as usize;
        off += 1;
        if len == 0 {
            break;
        }
        if len > 63 || off + len > buf.len() {
            return None;
        }
        labels.push(&buf[off..off + len]);
        off += len;
    }
    if off + 4 > buf.len() {
        return None;
    }
    let qtype = u16::from_be_bytes([buf[off], buf[off + 1]]);
    let mut name = String::new();
    for (i, l) in labels.iter().enumerate() {
        if i > 0 {
            name.push('.');
        }
        for &b in l.iter() {
            name.push(b as char);
        }
    }
    Some((name.to_ascii_lowercase(), qtype, off + 4))
}

/// 把应答写回 TUN 的公共封装。
fn send_dns_reply(stack: &TunStack, client: std::net::SocketAddr, server: std::net::SocketAddr, query: &[u8],
                  domain: &str, qtype: u16, a: Option<[u8; 4]>, question_len: usize) {
    send_dns_reply_rcode(stack, client, server, query, domain, qtype, a, question_len, 0);
}

/// 带 RCODE 的应答封装 (rcode=3 用于 SERVFAIL)。
fn send_dns_reply_rcode(stack: &TunStack, client: std::net::SocketAddr, server: std::net::SocketAddr, query: &[u8],
                        domain: &str, qtype: u16, a: Option<[u8; 4]>, question_len: usize, rcode: u8) {
    if let Some(resp) = build_response(query, domain, qtype, a, question_len, rcode) {
        if let Some(pkt) = crate::tun::udp::build_reply_ip_public(server, client, &resp) {
            stack.write_raw(&pkt);
        }
    }
}

/// DNS 查询计数 (流量监测用)。
pub static DNS_QUERIES: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// DNS 查询入口 (mod.rs 调用): 全量 Fake-IP 极速架构 (0ms 秒回，彻底免疫 GFW 污染与排队)。
pub fn handle_dns_query(stack: Arc<TunStack>, client: std::net::SocketAddr, server: std::net::SocketAddr, query: &[u8]) {
    use crate::direct;
    let Some((domain, qtype, question_len)) = parse_query(query) else { return };
    DNS_QUERIES.fetch_add(1, std::sync::atomic::Ordering::Relaxed);

    let (decision, matched_rule) = direct::route_decision_detailed(Some(&domain), None, Some(53), Some("udp"));

    if decision == direct::RuleAction::Block {
        let (cid, _conn_up, _conn_down, _) = crate::monitor::record_conn_start(
            "DNS",
            &format!("{domain}:53"),
            &client.ip().to_string(),
            &matched_rule,
            "BLOCK",
        );
        tracing::info!("[TUN-DNS] 规则拦截 (Block): {} (qtype={}) from {}", domain, qtype, client);
        let a = if qtype == 1 { Some([0, 0, 0, 0]) } else { None };
        send_dns_reply(&stack, client, server, query, &domain, qtype, a, question_len);
        crate::monitor::record_conn_close(cid, query.len() as u64, 64, "Blocked");
        return;
    }

    if qtype == 1 {
        // A 记录: 全量统一分配 Fake-IP (0ms 秒回，避免上游 DNS 排队与 GFW 污染注入系统 DNS 缓存)
        let a = stack.engine().fake_ip_allocate(&domain).map(|ip| ip.octets());
        let fake_ip_str = if let Some(ref oct) = a {
            tracing::debug!("[TUN-DNS] Fake-IP 分配: {} → 198.18.{}.{} (qtype=1) from {}", domain, oct[2], oct[3], client);
            format!("198.18.{}.{}", oct[2], oct[3])
        } else {
            "198.18.0.2".to_string()
        };
        let (cid, _conn_up, _conn_down, _) = crate::monitor::record_conn_start(
            "DNS",
            &format!("{domain}:53"),
            &fake_ip_str,
            &matched_rule,
            "Fake-IP",
        );
        send_dns_reply(&stack, client, server, query, &domain, qtype, a, question_len);
        crate::monitor::record_conn_close(cid, query.len() as u64, 64, "Resolved (Fake-IP)");
    } else {
        // 非 A 记录 (AAAA/HTTPS/TXT): 返回空应答 (NOERROR)，引导客户端立即回退 IPv4
        tracing::debug!("[TUN-DNS] 非 A 记录查询: {} (type={}) from {} → 空应答", domain, qtype, client);
        send_dns_reply(&stack, client, server, query, &domain, qtype, None, question_len);
    }
}

/// UDP DNS 应答 (兼容旧接口, TCP DNS relay 用; fake-IP 路径)。
pub fn answer_dns_udp(engine: &Engine, payload: &[u8]) -> Option<Vec<u8>> {
    let (domain, qtype, question_len) = parse_query(payload)?;
    debug!("[TUN-DNS] 查询 {} (type {})", domain, qtype);
    let a = if qtype == 1 {
        engine.fake_ip_allocate(&domain).map(|ip| ip.octets())
    } else {
        None
    };
    build_response(payload, &domain, qtype, a, question_len, 0)
}

/// TCP DNS relay: 读 [2B len][query] → 回 [2B len][response] → 关闭。
pub async fn relay_tcp_dns(stack: Arc<TunStack>, handle: SocketHandle) {
    let stream = match TunTcpStream::from_catcher(stack.clone(), handle).await {
        Ok(s) => s,
        Err(_) => return,
    };
    let mut stream = stream;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    loop {
        let mut len_buf = [0u8; 2];
        if stream.read_exact(&mut len_buf).await.is_err() {
            break;
        }
        let qlen = u16::from_be_bytes(len_buf) as usize;
        if qlen == 0 || qlen > 4096 {
            break;
        }
        let mut query = vec![0u8; qlen];
        if stream.read_exact(&mut query).await.is_err() {
            break;
        }
        // TCP DNS 也走分流 (国内→真实 IP)
        if let Some((domain, qtype, question_len)) = parse_query(&query) {
            if qtype == 1 && crate::direct::should_direct(Some(&domain), None) {
                if let Some(ip) = resolve_upstream(&domain).await {
                    let resp = build_response(&query, &domain, qtype, Some(ip.octets()), question_len, 0).unwrap_or_default();
                    let mut framed = Vec::with_capacity(2 + resp.len());
                    framed.extend_from_slice(&(resp.len() as u16).to_be_bytes());
                    framed.extend_from_slice(&resp);
                    if stream.write_all(&framed).await.is_err() {
                        break;
                    }
                    continue;
                }
            }
        }
        let resp = answer_dns_udp(&stack.engine(), &query).unwrap_or_default();
        let mut framed = Vec::with_capacity(2 + resp.len());
        framed.extend_from_slice(&(resp.len() as u16).to_be_bytes());
        framed.extend_from_slice(&resp);
        if stream.write_all(&framed).await.is_err() {
            break;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dns_response_ttl_is_60s() {
        let query = build_a_query("example.com", 0x1234);
        let qname_end = {
            let mut p = 12usize;
            while p < query.len() && query[p] != 0 {
                p += 1 + query[p] as usize;
            }
            p - 12 + 1
        };
        let resp = build_response(&query, "example.com", 1, Some([198, 18, 0, 2]), 12 + qname_end + 4, 0).unwrap();
        // A 记录部分: [C0 0C][00 01][00 01][TTL 4B][00 04][IP 4B]
        let a_offset = resp.len() - 16;
        let ttl_bytes = &resp[a_offset + 6..a_offset + 10];
        let ttl = u32::from_be_bytes([ttl_bytes[0], ttl_bytes[1], ttl_bytes[2], ttl_bytes[3]]);
        assert_eq!(ttl, 60, "Fake-IP 应答 TTL 必须为 60s");
    }

    #[test]
    fn servfail_rcode_is_3() {
        let query = build_a_query("example.com", 0x5678);
        let qname_end = {
            let mut p = 12usize;
            while p < query.len() && query[p] != 0 {
                p += 1 + query[p] as usize;
            }
            p - 12 + 1
        };
        // rcode=3 → flags 低字节 0x80|0x03 = 0x83 (0x8183 = SERVFAIL, RA 置位)
        let resp = build_response(&query, "example.com", 1, None, 12 + qname_end + 4, 3).unwrap();
        assert_eq!(resp[2], 0x81, "QR+RD");
        assert_eq!(resp[3], 0x83, "RA + RCODE=3 (SERVFAIL)");
        assert_eq!(resp[6], 0, "ANSWER 数必须为 0 (SERVFAIL 无答案)");
        // rcode=0 时 flags 应为 0x8180 (NOERROR)
        let ok = build_response(&query, "example.com", 1, None, 12 + qname_end + 4, 0).unwrap();
        assert_eq!(ok[3], 0x80, "NOERROR");
    }

    #[test]
    fn direct_cache_clear_works() {
        {
            let mut map = direct_cache().lock().unwrap_or_else(|e| e.into_inner());
            map.insert("baidu.com".to_string(), (std::net::Ipv4Addr::new(220, 181, 38, 148), Instant::now()));
        }
        assert!(direct_dns_lookup("baidu.com").is_some());
        clear_direct_cache();
        assert!(direct_dns_lookup("baidu.com").is_none());
    }
}
