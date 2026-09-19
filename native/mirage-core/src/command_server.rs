//! 统一命令总线流式推送服务 (Command Bus & Telemetry Server)。
//!
//! 在 Linux / Android 抽象命名空间 Unix 域套接字 `@mirage_cmd.sock` 提供流式双向 IPC:
//! - 规避 Android Binder 1MB 事务大小限制 (`TransactionTooLargeException`)；
//! - 毫秒级流式推送速率指标、最新日志与连接快照；
//! - 允许客户端无轮询地订阅状态与发送定向控制指令 (如关闭指定连接)。

use std::sync::Arc;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tracing::{debug, info, warn};

pub const ABSTRACT_SOCKET_NAME: &[u8] = b"mirage_cmd.sock";

#[cfg(any(target_os = "linux", target_os = "android"))]
static ACTIVE_CLIENTS: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
#[cfg(any(target_os = "linux", target_os = "android"))]
const MAX_CONCURRENT_CLIENTS: usize = 2;

#[cfg(any(target_os = "linux", target_os = "android"))]
struct ClientGuard;

#[cfg(any(target_os = "linux", target_os = "android"))]
impl Drop for ClientGuard {
    fn drop(&mut self) {
        ACTIVE_CLIENTS.fetch_sub(1, std::sync::atomic::Ordering::SeqCst);
    }
}

/// 启动内嵌命令总线服务端 (基于 Linux / Android 抽象 Unix 域套接字)
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn start_command_server(
    stop_notify: Arc<tokio::sync::Notify>,
    handle: Option<&tokio::runtime::Handle>,
) {
    #[cfg(target_os = "android")]
    use std::os::android::net::SocketAddrExt;
    #[cfg(target_os = "linux")]
    use std::os::linux::net::SocketAddrExt;
    use std::os::unix::net::UnixListener as StdUnixListener;

    let fut = async move {
        let addr = match std::os::unix::net::SocketAddr::from_abstract_name(ABSTRACT_SOCKET_NAME) {
            Ok(a) => a,
            Err(e) => {
                warn!("[CMD-BUS] 创建抽象 Unix 套接字地址失败: {e}");
                return;
            }
        };

        let std_listener = match StdUnixListener::bind_addr(&addr) {
            Ok(l) => l,
            Err(e) => {
                warn!("[CMD-BUS] 绑定抽象 Unix 域套接字失败: {e}");
                return;
            }
        };

        if let Err(e) = std_listener.set_nonblocking(true) {
            warn!("[CMD-BUS] 设置非阻塞失败: {e}");
            return;
        }

        let listener = match tokio::net::UnixListener::from_std(std_listener) {
            Ok(l) => {
                info!("[CMD-BUS] 统一命令总线抽象套接字已就绪: @mirage_cmd.sock");
                l
            }
            Err(e) => {
                warn!("[CMD-BUS] 转换为 tokio 监听器失败: {e}");
                return;
            }
        };

        loop {
            tokio::select! {
                _ = stop_notify.notified() => {
                    info!("[CMD-BUS] 收到停止信号，退出命令总线服务");
                    break;
                }
                res = listener.accept() => {
                    let (socket, _) = match res {
                        Ok(conn) => conn,
                        Err(e) => {
                            debug!("[CMD-BUS] accept 错误: {e}");
                            break;
                        }
                    };

                    // N2: 纵深防御，使用 SO_PEERCRED 严格校验对端 UID 等于当前进程 UID
                    let my_uid = unsafe { libc::getuid() };
                    match socket.peer_cred() {
                        Ok(cred) => {
                            if cred.uid() != my_uid {
                                warn!(
                                    "[CMD-BUS] 拒绝非本应用 UID 连接: peer_uid={}, my_uid={}",
                                    cred.uid(),
                                    my_uid
                                );
                                continue;
                            }
                        }
                        Err(e) => {
                            warn!("[CMD-BUS] 获取对端凭据 (SO_PEERCRED) 失败: {e}");
                            continue;
                        }
                    }

                    // N5: 限制最大并发客户端数，防止连接泄露与重复序列化开销
                    if ACTIVE_CLIENTS.fetch_add(1, std::sync::atomic::Ordering::SeqCst) >= MAX_CONCURRENT_CLIENTS {
                        ACTIVE_CLIENTS.fetch_sub(1, std::sync::atomic::Ordering::SeqCst);
                        warn!("[CMD-BUS] 达到最大并发客户端限制 ({MAX_CONCURRENT_CLIENTS})，拒绝新连接");
                        continue;
                    }

                    let client_stop = stop_notify.clone();
                    tokio::spawn(async move {
                        let _guard = ClientGuard;
                        handle_client(socket, client_stop).await;
                    });
                }
            }
        }
    };

    if let Some(h) = handle {
        h.spawn(fut);
    } else if let Ok(h) = tokio::runtime::Handle::try_current() {
        h.spawn(fut);
    } else {
        warn!("[CMD-BUS] 未在 Tokio 运行时环境中，且未提供 Handle，跳过启动命令总线服务");
    }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn start_command_server(
    _stop_notify: Arc<tokio::sync::Notify>,
    _handle: Option<&tokio::runtime::Handle>,
) {
    // 非 Linux / Android 平台桩函数
}

#[cfg(any(target_os = "linux", target_os = "android"))]
async fn handle_client(socket: tokio::net::UnixStream, stop_notify: Arc<tokio::sync::Notify>) {
    let (reader, mut writer) = socket.into_split();
    let mut reader = BufReader::new(reader);

    // N4: 设置 MissedTickBehavior::Skip，防止休眠唤醒或调度延迟后突发补发堆积
    let mut tick_timer = tokio::time::interval(std::time::Duration::from_millis(1000));
    tick_timer.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

    let mut reqs_timer = tokio::time::interval(std::time::Duration::from_millis(2000));
    reqs_timer.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

    let mut line_buf = String::new();

    loop {
        line_buf.clear();
        tokio::select! {
            _ = stop_notify.notified() => break,
            _ = tick_timer.tick() => {
                // 1. 推送实时吞吐指标
                let (up, down, up_rate, down_rate) = crate::monitor::sample();
                let tcp = crate::tun::tcp::TCP_ACTIVE.load(std::sync::atomic::Ordering::Relaxed);
                let udp = crate::tun::udp::flow_count_global();
                let dns = crate::tun::dns::DNS_QUERIES.load(std::sync::atomic::Ordering::Relaxed);
                let payload = serde_json::json!({
                    "event": "stats",
                    "up": up,
                    "down": down,
                    "up_rate": up_rate,
                    "down_rate": down_rate,
                    "tcp": tcp,
                    "udp": udp,
                    "dns": dns
                });
                if writer.write_all(format!("{}\n", payload).as_bytes()).await.is_err() {
                    break;
                }
            }
            _ = reqs_timer.tick() => {
                // 2. 推送近期请求流快照 (N3: 零二次序列化，流式直接拼接消除二次转义与字符串包装)
                let reqs_json = crate::monitor::get_recent_requests_json();
                let mut buf = Vec::with_capacity(reqs_json.len() + 36);
                buf.extend_from_slice(b"{\"event\":\"recent_requests\",\"data\":");
                buf.extend_from_slice(reqs_json.as_bytes());
                buf.extend_from_slice(b"}\n");
                if writer.write_all(&buf).await.is_err() {
                    break;
                }
            }
            read_res = reader.read_line(&mut line_buf) => {
                match read_res {
                    Ok(0) => break, // 对端正常断开
                    Ok(_) => {
                        let trimmed = line_buf.trim();
                        if trimmed.is_empty() { continue; }
                        if let Ok(cmd) = serde_json::from_str::<serde_json::Value>(trimmed) {
                            let action = cmd.get("action").and_then(|v| v.as_str()).unwrap_or("");
                            match action {
                                "ping" => {
                                    let _ = writer.write_all(b"{\"event\":\"pong\"}\n").await;
                                }
                                "close_connection" => {
                                    if let Some(id) = cmd.get("id").and_then(|v| v.as_u64()) {
                                        let ok = crate::monitor::close_connection(id);
                                        let reply = serde_json::json!({
                                            "event": "close_result",
                                            "id": id,
                                            "success": ok
                                        });
                                        let _ = writer.write_all(format!("{}\n", reply).as_bytes()).await;
                                    }
                                }
                                _ => {}
                            }
                        }
                    }
                    Err(_) => break,
                }
            }
        }
    }
}

#[cfg(all(test, any(target_os = "linux", target_os = "android")))]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_command_server_handle_client_ping_pong() {
        let (client, server) = tokio::net::UnixStream::pair().expect("pair failed");
        let stop_notify = Arc::new(tokio::sync::Notify::new());
        let server_stop = stop_notify.clone();

        let srv_handle = tokio::spawn(async move {
            let _guard = ClientGuard;
            handle_client(server, server_stop).await;
        });

        let (reader, mut writer) = client.into_split();
        let mut reader = BufReader::new(reader);

        writer
            .write_all(b"{\"action\":\"ping\"}\n")
            .await
            .expect("write ping");

        let mut line = String::new();
        let mut got_pong = false;
        for _ in 0..10 {
            line.clear();
            let n = reader.read_line(&mut line).await.expect("read line");
            if n == 0 {
                break;
            }
            if line.contains("\"event\":\"pong\"") {
                got_pong = true;
                break;
            }
        }
        assert!(got_pong, "Expected pong response from command server");

        stop_notify.notify_waiters();
        let _ = srv_handle.await;
    }

    #[test]
    fn test_client_guard_atomic_counter() {
        let initial = ACTIVE_CLIENTS.load(std::sync::atomic::Ordering::SeqCst);
        ACTIVE_CLIENTS.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        {
            let _g1 = ClientGuard;
            assert_eq!(
                ACTIVE_CLIENTS.load(std::sync::atomic::Ordering::SeqCst),
                initial + 1
            );
        }
        assert_eq!(
            ACTIVE_CLIENTS.load(std::sync::atomic::Ordering::SeqCst),
            initial
        );
    }
}
