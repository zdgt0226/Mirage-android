//! 智能流量嗅探器 (Protocol & Domain Sniffer)
//!
//! 在 TUN 接口拦截到首包时，解析明文协议头部并快速提取目标域名 (SNI / Host)。
//! 支持:
//! - TLS 1.2 / 1.3 ClientHello: 提取 Server Name Indication (SNI)
//! - HTTP 1.1: 提取 Host 请求头
//! - QUIC / HTTP3: 提取 Initial ClientHello 中的 SNI

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum SniffProtocol {
    #[default]
    Unknown,
    Tls,
    Http,
    Quic,
}

impl SniffProtocol {
    pub fn as_str(&self) -> &'static str {
        match self {
            SniffProtocol::Tls => "tls",
            SniffProtocol::Http => "http",
            SniffProtocol::Quic => "quic",
            SniffProtocol::Unknown => "unknown",
        }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SniffResult {
    pub protocol: SniffProtocol,
    pub host: Option<String>,
}

pub struct Sniffer;

impl Sniffer {
    /// 嗅探 TCP 首包 (TLS / HTTP)
    pub fn sniff_tcp(payload: &[u8]) -> SniffResult {
        if payload.is_empty() {
            return SniffResult::default();
        }

        // 1. 尝试 TLS ClientHello 嗅探
        if let Some(sni) = Self::parse_tls_sni(payload) {
            return SniffResult {
                protocol: SniffProtocol::Tls,
                host: Some(sni),
            };
        }

        // 2. 尝试 HTTP 1.1 Host 嗅探
        if let Some(host) = Self::parse_http_host(payload) {
            return SniffResult {
                protocol: SniffProtocol::Http,
                host: Some(host),
            };
        }

        SniffResult::default()
    }

    /// 嗅探 UDP 首包 (QUIC / HTTP3)
    pub fn sniff_udp(payload: &[u8]) -> SniffResult {
        if payload.len() < 12 {
            return SniffResult::default();
        }

        // QUIC Long Header Initial: 0x80 | 0x40 (Fixed bit) | Type (0x00 for v1 Initial)
        let first_byte = payload[0];
        if (first_byte & 0x80) != 0 && (first_byte & 0x40) != 0 {
            if let Some(sni) = Self::parse_quic_sni(payload) {
                return SniffResult {
                    protocol: SniffProtocol::Quic,
                    host: Some(sni),
                };
            }
        }

        SniffResult::default()
    }

    /// 解析 TLS ClientHello 提取 SNI 扩展
    pub fn parse_tls_sni(buf: &[u8]) -> Option<String> {
        // Record Header (5 字节): [0x16, major, minor, len_hi, len_lo]
        if buf.len() < 5 || buf[0] != 0x16 {
            return None;
        }

        let record_len = u16::from_be_bytes([buf[3], buf[4]]) as usize;
        let data = if buf.len() >= 5 + record_len {
            &buf[5..5 + record_len]
        } else {
            &buf[5..]
        };

        // Handshake Header: Type 0x01 (ClientHello), Length 3 字节
        if data.len() < 4 || data[0] != 0x01 {
            return None;
        }

        let mut cur = 4; // 跳过 Handshake Header
        if cur + 2 > data.len() { return None; }
        cur += 2; // 跳过 Client Version (2 字节)

        if cur + 32 > data.len() { return None; }
        cur += 32; // 跳过 Random (32 字节)

        // Session ID
        if cur >= data.len() { return None; }
        let session_id_len = data[cur] as usize;
        cur += 1 + session_id_len;

        // Cipher Suites
        if cur + 2 > data.len() { return None; }
        let cipher_suites_len = u16::from_be_bytes([data[cur], data[cur + 1]]) as usize;
        cur += 2 + cipher_suites_len;

        // Compression Methods
        if cur >= data.len() { return None; }
        let comp_methods_len = data[cur] as usize;
        cur += 1 + comp_methods_len;

        // Extensions
        if cur + 2 > data.len() { return None; }
        let extensions_len = u16::from_be_bytes([data[cur], data[cur + 1]]) as usize;
        cur += 2;

        let ext_end = (cur + extensions_len).min(data.len());
        while cur + 4 <= ext_end {
            let ext_type = u16::from_be_bytes([data[cur], data[cur + 1]]);
            let ext_len = u16::from_be_bytes([data[cur + 2], data[cur + 3]]) as usize;
            cur += 4;

            if cur + ext_len > ext_end {
                break;
            }

            // SNI 扩展类型为 0x0000
            if ext_type == 0x0000 {
                let ext_data = &data[cur..cur + ext_len];
                if ext_data.len() >= 2 {
                    let mut sni_cur = 2; // 跳过 server_name_list 长度 (2 字节)
                    while sni_cur + 3 <= ext_data.len() {
                        let name_type = ext_data[sni_cur];
                        let name_len = u16::from_be_bytes([ext_data[sni_cur + 1], ext_data[sni_cur + 2]]) as usize;
                        sni_cur += 3;

                        if name_type == 0x00 && sni_cur + name_len <= ext_data.len() {
                            let name_bytes = &ext_data[sni_cur..sni_cur + name_len];
                            if let Ok(host) = std::str::from_utf8(name_bytes) {
                                return Some(host.trim_end_matches('.').to_ascii_lowercase());
                            }
                        }
                        sni_cur += name_len;
                    }
                }
            }
            cur += ext_len;
        }

        None
    }

    /// 解析 HTTP 1.1 请求提取 Host 头
    pub fn parse_http_host(buf: &[u8]) -> Option<String> {
        // 检查常见的 HTTP 方法
        let methods: &[&[u8]] = &[b"GET ", b"POST ", b"HEAD ", b"PUT ", b"DELETE ", b"OPTIONS ", b"CONNECT ", b"PATCH "];
        let is_http = methods.iter().any(|m| buf.starts_with(m));
        if !is_http {
            return None;
        }

        // 在前 1024 字节中查找 Host:
        let search_len = buf.len().min(1024);
        let header_str = std::str::from_utf8(&buf[..search_len]).ok()?;

        for line in header_str.lines() {
            let trimmed = line.trim();
            if trimmed.is_empty() {
                break;
            }
            if let Some(colon_idx) = trimmed.find(':') {
                let key = trimmed[..colon_idx].trim();
                if key.eq_ignore_ascii_case("Host") {
                    let mut host_val = trimmed[colon_idx + 1..].trim();
                    // 剥离端口号 (如 example.com:8080)
                    if let Some(port_idx) = host_val.rfind(':') {
                        // 避免误伤 IPv6 [::1]:80
                        if !host_val.starts_with('[') || host_val.ends_with(']') {
                            host_val = &host_val[..port_idx];
                        }
                    }
                    let cleaned = host_val.trim_matches(|c| c == '[' || c == ']').trim_end_matches('.');
                    if !cleaned.is_empty() {
                        return Some(cleaned.to_ascii_lowercase());
                    }
                }
            }
        }

        None
    }

    /// 解析 QUIC 初始包提取 SNI
    pub fn parse_quic_sni(buf: &[u8]) -> Option<String> {
        let limit = buf.len().min(1200);
        for i in 0..limit.saturating_sub(40) {
            if buf[i] == 0x01 {
                let fake_record = [
                    &[0x16, 0x03, 0x03, 0x00, 0x00][..],
                    &buf[i..],
                ].concat();
                if let Some(sni) = Self::parse_tls_sni(&fake_record) {
                    return Some(sni);
                }
            }
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_http_host() {
        let req = b"GET /index.html HTTP/1.1\r\nHost: www.Google.com:443\r\nUser-Agent: curl/7.68.0\r\nAccept: */*\r\n\r\n";
        let res = Sniffer::sniff_tcp(req);
        assert_eq!(res.protocol, SniffProtocol::Http);
        assert_eq!(res.host.as_deref(), Some("www.google.com"));
    }

    #[test]
    fn test_parse_tls_sni_synthetic() {
        let host = b"mirage.proxy";
        let mut sni_ext = Vec::new();
        sni_ext.extend_from_slice(&[0x00, 0x00]);
        let ext_len = (2 + 1 + 2 + host.len()) as u16;
        sni_ext.extend_from_slice(&ext_len.to_be_bytes());
        let list_len = (1 + 2 + host.len()) as u16;
        sni_ext.extend_from_slice(&list_len.to_be_bytes());
        sni_ext.push(0x00);
        sni_ext.extend_from_slice(&(host.len() as u16).to_be_bytes());
        sni_ext.extend_from_slice(host);

        let mut hello = Vec::new();
        hello.extend_from_slice(&[0x03, 0x03]);
        hello.extend_from_slice(&[0u8; 32]);
        hello.push(0x00);
        hello.extend_from_slice(&[0x00, 0x02, 0x13, 0x01]);
        hello.extend_from_slice(&[0x01, 0x00]);
        hello.extend_from_slice(&(sni_ext.len() as u16).to_be_bytes());
        hello.extend_from_slice(&sni_ext);

        let mut record = Vec::new();
        record.push(0x16);
        record.extend_from_slice(&[0x03, 0x01]);
        let handshake_len = (4 + hello.len()) as u16;
        record.extend_from_slice(&handshake_len.to_be_bytes());
        record.push(0x01);
        record.extend_from_slice(&(hello.len() as u32).to_be_bytes()[1..4]);
        record.extend_from_slice(&hello);

        let res = Sniffer::sniff_tcp(&record);
        assert_eq!(res.protocol, SniffProtocol::Tls);
        assert_eq!(res.host.as_deref(), Some("mirage.proxy"));
    }
}
