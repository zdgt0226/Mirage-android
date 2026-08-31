//! 远程调试服务 (PoC Debug Server)。
//!
//! 在 `127.0.0.1:9090` 提供轻量级 HTTP REST 接口，支持：
//! - 系统健康快照 (`GET /debug/snapshot`)
//! - 动态路由判决演练 (`POST /debug/route`)
//! - DNS / Fake-IP 状态与统计 (`GET /debug/dns`, `GET /debug/fake-ip`)
//! - 活跃连接监控 (`GET /debug/conns`)
//! - 运行时动态控制 (`POST /debug/control`)

use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tracing::{debug, info, warn};

use crate::engine::Engine;
use crate::tun::TunStack;

/// 启动内嵌轻量级调试 HTTP 服务
pub fn start_debug_server(port: u16, engine: Arc<Engine>, stack: Arc<TunStack>) {
    tokio::spawn(async move {
        let addr = format!("0.0.0.0:{port}");
        let listener = match TcpListener::bind(&addr).await {
            Ok(l) => {
                info!("[DEBUG-SERVER] 远程调试 API 已就绪: http://{}", addr);
                l
            }
            Err(e) => {
                warn!("[DEBUG-SERVER] 绑定端口 {port} 失败: {e}");
                return;
            }
        };

        loop {
            let (mut socket, client_addr) = match listener.accept().await {
                Ok(conn) => conn,
                Err(_) => break,
            };

            let eng = Arc::clone(&engine);
            let stk = Arc::clone(&stack);

            tokio::spawn(async move {
                let mut buf = [0u8; 4096];
                let n = match tokio::time::timeout(std::time::Duration::from_secs(5), socket.read(&mut buf)).await {
                    Ok(Ok(n)) if n > 0 => n,
                    _ => return,
                };

                let req_str = String::from_utf8_lossy(&buf[..n]);
                let mut lines = req_str.lines();
                let request_line = lines.next().unwrap_or("");
                let mut parts = request_line.split_whitespace();
                let method = parts.next().unwrap_or("GET");
                let path = parts.next().unwrap_or("/");

                // 提取请求体 (POST)
                let body = if let Some(idx) = req_str.find("\r\n\r\n") {
                    &req_str[idx + 4..]
                } else if let Some(idx) = req_str.find("\n\n") {
                    &req_str[idx + 2..]
                } else {
                    ""
                };

                let (status, resp_json) = handle_request(method, path, body, &eng, &stk);

                let resp_payload = resp_json.to_string();
                let http_response = format!(
                    "HTTP/1.1 {}\r\n\
                    Content-Type: application/json; charset=utf-8\r\n\
                    Content-Length: {}\r\n\
                    Access-Control-Allow-Origin: *\r\n\
                    Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n\
                    Access-Control-Allow-Headers: Content-Type\r\n\
                    Connection: close\r\n\r\n{}",
                    status,
                    resp_payload.len(),
                    resp_payload
                );

                let _ = socket.write_all(http_response.as_bytes()).await;
                debug!("[DEBUG-SERVER] {} {} from {} → {}", method, path, client_addr, status);
            });
        }
    });
}

fn handle_request(
    method: &str,
    path: &str,
    body: &str,
    engine: &Arc<Engine>,
    stack: &Arc<TunStack>,
) -> (&'static str, serde_json::Value) {
    if method == "OPTIONS" {
        return ("200 OK", serde_json::json!({"status": "ok"}));
    }

    let clean_path = path.split('?').next().unwrap_or(path);

    match (method, clean_path) {
        ("GET", "/") | ("GET", "/debug/ping") => (
            "200 OK",
            serde_json::json!({
                "status": "ok",
                "version": "0.2.10",
                "server": "mirage-core-debug",
                "healthy": engine.is_healthy(),
            }),
        ),

        ("GET", "/debug/snapshot") => {
            let up = crate::monitor::GLOBAL_UP.load(std::sync::atomic::Ordering::Relaxed);
            let down = crate::monitor::GLOBAL_DOWN.load(std::sync::atomic::Ordering::Relaxed);
            let tcp = crate::tun::tcp::TCP_ACTIVE.load(std::sync::atomic::Ordering::Relaxed);
            let flow_cnt = crate::tun::udp::flow_count_global();
            let queries_cnt = crate::tun::dns::DNS_QUERIES.load(std::sync::atomic::Ordering::Relaxed);

            // 获取 /proc/self 状态 (Linux / Android)
            let fd_count = get_proc_fd_count();
            let rss_kb = get_proc_rss_kb();

            (
                "200 OK",
                serde_json::json!({
                    "engine_healthy": engine.is_healthy(),
                    "active_tcp_connections": tcp,
                    "active_udp_flows": flow_cnt,
                    "dns_queries_count": queries_cnt,
                    "total_upload_bytes": up,
                    "total_download_bytes": down,
                    "process_fd_count": fd_count,
                    "process_rss_kb": rss_kb,
                    "block_quic": crate::direct::is_block_quic(),
                }),
            )
        }

        ("GET", "/debug/dns") => {
            (
                "200 OK",
                serde_json::json!({
                    "direct_dns": crate::tun::dns::get_direct_dns().to_string(),
                    "remote_dns": crate::tun::dns::get_remote_dns().to_string(),
                    "total_queries": crate::tun::dns::DNS_QUERIES.load(std::sync::atomic::Ordering::Relaxed),
                    "mode": "Pure Fake-IP (0ms Immunity)",
                }),
            )
        }

        ("GET", "/debug/fake-ip") => {
            (
                "200 OK",
                serde_json::json!({
                    "pool_range": "198.18.0.0/16",
                    "mode": "Full Intercept (All A records mapped to 198.18.x.x)",
                }),
            )
        }

        ("POST", "/debug/route") => {
            let parsed: serde_json::Value = serde_json::from_str(body).unwrap_or(serde_json::Value::Null);
            let domain = parsed.get("domain").and_then(|v| v.as_str());
            let ip_str = parsed.get("ip").and_then(|v| v.as_str());
            let port = parsed.get("port").and_then(|v| v.as_u64()).map(|p| p as u16);
            let proto = parsed.get("proto").and_then(|v| v.as_str()).unwrap_or("tcp");

            let ip = ip_str.and_then(|s| s.parse::<std::net::IpAddr>().ok());

            let decision = crate::direct::route_decision(domain, ip, port, Some(proto));
            let is_strict_cn = domain.map(crate::direct::is_cn_domain_strict).unwrap_or(false);

            (
                "200 OK",
                serde_json::json!({
                    "domain": domain,
                    "ip": ip_str,
                    "port": port,
                    "protocol": proto,
                    "decision": decision.as_str(),
                    "is_strict_cn_domain": is_strict_cn,
                }),
            )
        }

        ("GET", "/debug/conns") | ("GET", "/debug/connections") => {
            let conns_str = crate::monitor::get_connections_json();
            let conns_json: serde_json::Value = serde_json::from_str(&conns_str).unwrap_or_else(|_| serde_json::json!([]));

            (
                "200 OK",
                serde_json::json!({
                    "connections": conns_json,
                }),
            )
        }

        ("GET", "/debug/traffic-profiles") | ("GET", "/debug/profiles") => {
            let profiles_str = crate::tun::adaptive_idle::get_learned_profiles_json();
            let profiles_json: serde_json::Value = serde_json::from_str(&profiles_str).unwrap_or_else(|_| serde_json::json!([]));

            (
                "200 OK",
                serde_json::json!({
                    "traffic_profiles": profiles_json,
                    "total_profiles_count": profiles_json.as_array().map(|a| a.len()).unwrap_or(0),
                }),
            )
        }

        ("GET", "/requests") | ("GET", "/debug/requests") => {
            let reqs_json: serde_json::Value = serde_json::from_str(&crate::monitor::get_recent_requests_json())
                .unwrap_or_else(|_| serde_json::json!([]));
            (
                "200 OK",
                serde_json::json!({
                    "requests": reqs_json,
                    "count": reqs_json.as_array().map(|a| a.len()).unwrap_or(0),
                }),
            )
        }

        ("GET", "/mode") | ("GET", "/debug/mode") => {
            let mode = crate::direct::get_outbound_mode();
            (
                "200 OK",
                serde_json::json!({
                    "mode": mode,
                    "mode_name": match mode {
                        1 => "GlobalProxy",
                        2 => "Direct",
                        _ => "Rule",
                    }
                }),
            )
        }

        ("POST", "/mode") | ("POST", "/debug/mode") => {
            let parsed: serde_json::Value = serde_json::from_str(body).unwrap_or(serde_json::Value::Null);
            let mode = parsed.get("mode").and_then(|v| v.as_u64()).unwrap_or(0) as u8;
            crate::direct::set_outbound_mode(mode);
            (
                "200 OK",
                serde_json::json!({
                    "success": true,
                    "mode": mode,
                    "mode_name": match mode {
                        1 => "GlobalProxy",
                        2 => "Direct",
                        _ => "Rule",
                    }
                }),
            )
        }

        ("GET", "/logs") | ("GET", "/debug/logs") => {
            let logs = crate::monitor::drain_recent_logs();
            (
                "200 OK",
                serde_json::json!({
                    "logs": logs,
                    "count": logs.len()
                }),
            )
        }

        ("GET", "/traffic") | ("GET", "/debug/traffic") => {
            let (up_total, down_total, up_rate, down_rate) = crate::monitor::sample();
            let tcp = crate::tun::tcp::TCP_ACTIVE.load(std::sync::atomic::Ordering::Relaxed);
            let udp = crate::tun::udp::flow_count_global();
            let dns = crate::tun::dns::DNS_QUERIES.load(std::sync::atomic::Ordering::Relaxed);

            (
                "200 OK",
                serde_json::json!({
                    "up_total_bytes": up_total,
                    "down_total_bytes": down_total,
                    "up_rate_bps": up_rate,
                    "down_rate_bps": down_rate,
                    "active_tcp": tcp,
                    "active_udp": udp,
                    "total_dns_queries": dns,
                }),
            )
        }

        ("POST", "/rules") | ("POST", "/debug/rules") => {
            let ok = crate::direct::set_custom_rules(body);
            if ok {
                (
                    "200 OK",
                    serde_json::json!({"success": true, "message": "规则已安全清洗并热更新生效"}),
                )
            } else {
                (
                    "400 Bad Request",
                    serde_json::json!({"success": false, "error": "规则 JSON 解析失败"}),
                )
            }
        }

        ("DELETE", p) if p == "/connections" || p == "/debug/connections" => {
            let closed = crate::monitor::close_all_connections();
            (
                "200 OK",
                serde_json::json!({"success": true, "closed_connections_count": closed}),
            )
        }

        ("DELETE", p) if p.starts_with("/connections/") || p.starts_with("/debug/connections/") => {
            let id_str = p.rsplit('/').next().unwrap_or("");
            if let Ok(id) = id_str.parse::<u64>() {
                let found = crate::monitor::close_connection(id);
                if found {
                    (
                        "200 OK",
                        serde_json::json!({"success": true, "closed_conn_id": id}),
                    )
                } else {
                    (
                        "404 Not Found",
                        serde_json::json!({"success": false, "error": "connection not found or already closed"}),
                    )
                }
            } else {
                (
                    "400 Bad Request",
                    serde_json::json!({"success": false, "error": "invalid connection id"}),
                )
            }
        }

        ("POST", "/debug/control") => {
            let parsed: serde_json::Value = serde_json::from_str(body).unwrap_or(serde_json::Value::Null);
            let action = parsed.get("action").and_then(|v| v.as_str()).unwrap_or("");

            match action {
                "clear_dns" => {
                    crate::tun::dns::clear_direct_cache();
                    (
                        "200 OK",
                        serde_json::json!({"success": true, "message": "直连 DNS 缓存已清空"}),
                    )
                }
                "flush_pool" => {
                    stack.flush_pool();
                    (
                        "200 OK",
                        serde_json::json!({"success": true, "message": "连接池已冲刷"}),
                    )
                }
                "block_quic_on" => {
                    crate::direct::set_block_quic(true);
                    (
                        "200 OK",
                        serde_json::json!({"success": true, "block_quic": true}),
                    )
                }
                "block_quic_off" => {
                    crate::direct::set_block_quic(false);
                    (
                        "200 OK",
                        serde_json::json!({"success": true, "block_quic": false}),
                    )
                }
                "close_conn" => {
                    let id = parsed.get("id").and_then(|v| v.as_u64()).unwrap_or(0);
                    let found = crate::monitor::close_connection(id);
                    (
                        "200 OK",
                        serde_json::json!({"success": found, "closed_conn_id": id}),
                    )
                }
                "close_all_conns" => {
                    let closed = crate::monitor::close_all_connections();
                    (
                        "200 OK",
                        serde_json::json!({"success": true, "closed_connections_count": closed}),
                    )
                }
                _ => (
                    "400 Bad Request",
                    serde_json::json!({
                        "error": "unknown action",
                        "supported": ["clear_dns", "flush_pool", "block_quic_on", "block_quic_off", "close_conn", "close_all_conns"]
                    }),
                ),
            }
        }

        _ => (
            "404 Not Found",
            serde_json::json!({
                "error": "endpoint not found",
                "available_endpoints": [
                    "GET /debug/snapshot",
                    "GET /debug/dns",
                    "GET /debug/fake-ip",
                    "POST /debug/route",
                    "GET /debug/conns",
                    "GET /debug/traffic-profiles",
                    "POST /debug/control"
                ]
            }),
        ),
    }
}

fn get_proc_fd_count() -> usize {
    if let Ok(entries) = std::fs::read_dir("/proc/self/fd") {
        entries.count()
    } else {
        0
    }
}

fn get_proc_rss_kb() -> usize {
    if let Ok(content) = std::fs::read_to_string("/proc/self/statm") {
        let parts: Vec<&str> = content.split_whitespace().collect();
        if parts.len() >= 2 {
            if let Ok(pages) = parts[1].parse::<usize>() {
                return pages * 4; // 4KB per page
            }
        }
    }
    0
}
