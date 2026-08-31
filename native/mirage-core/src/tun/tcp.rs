//! TCP 数据面: smoltcp TcpSocket → tokio 流适配 + 与 Mirage 隧道的双向中继。
//!
//! 完全复用上游 WG 模块 `WgTcpStream` 的桥接模式:
//! - smoltcp 的**轮询式** socket 用 `register_recv_waker`/`register_send_waker` 接到
//!   tokio waker (需 `async` feature), 无数据时 Pending 而不是 sleep。
//! - 每次 `send_slice`/`recv_slice` 之后必须 `poll_now()` 驱动 smoltcp 生成 IP 包
//!   (否则要等下一个 25ms tick, 每个往返白加延迟)。
//! - socket 关闭必须**先 poll 再 remove**: FIN 是 poll 遍历仍在 SocketSet 里的 socket
//!   时才生成的; remove 在 poll 之后, 否则 FIN 永远发不出去。

use std::io;
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::task::{Context, Poll};

use smoltcp::iface::SocketHandle;
use smoltcp::socket::tcp;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadBuf};
use tracing::{debug, warn};

use crate::proxy::outbound::Address;
use crate::proxy::tunnel::Tunnel;
use crate::tun::{TunStack, SOCK_BUF, lock_inner};

/// 活跃 TCP 连接计数 (含隧道/直连, 流量监测用)。
pub static TCP_ACTIVE: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);

/// RAII: 退出时 TCP_ACTIVE-1。
struct TcpActiveGuard;
impl Drop for TcpActiveGuard {
    fn drop(&mut self) {
        TCP_ACTIVE.fetch_sub(1, std::sync::atomic::Ordering::Relaxed);
    }
}

/// 建连 (SYN→Established) 超时。catcher 被 SYN 接住后一般一个 RTT 内完成握手。
const CONNECT_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(15);

/// 一条已建立的 TCP 连接 (smoltcp 侧)。
pub struct TunTcpStream {
    stack: Arc<TunStack>,
    handle: SocketHandle,
    alive: Arc<AtomicBool>,
    /// 缓存的目标 (dst ip, dst port) —— socket 建立后从 local_endpoint 取一次。
    dst: Option<(std::net::IpAddr, u16)>,
}

impl TunTcpStream {
    /// 从 catcher socket 构造; 等到 Established 才返回。
    pub async fn from_catcher(stack: Arc<TunStack>, handle: SocketHandle) -> io::Result<Self> {
        let stream = Self {
            stack,
            handle,
            alive: Arc::new(AtomicBool::new(true)),
            dst: None,
        };
        match tokio::time::timeout(CONNECT_TIMEOUT, stream.wait_established()).await {
            Ok(r) => r?,
            Err(_) => {
                // 超时: stream 在此 drop, Drop 会 close + remove, 不留残骸。
                return Err(io::Error::new(io::ErrorKind::TimedOut, "TCP 握手超时"));
            }
        }
        Ok(stream)
    }

    async fn wait_established(&self) -> io::Result<()> {
        std::future::poll_fn(|cx| {
            let mut g = lock_inner(&self.stack.inner);
            if !g.sockets.iter().any(|(h, _)| h == self.handle) {
                return Poll::Ready(Err(io::Error::new(io::ErrorKind::ConnectionAborted, "连接已被清理")));
            }
            let sock = g.sockets.get_mut::<tcp::Socket>(self.handle);
            match sock.state() {
                tcp::State::Established => Poll::Ready(Ok(())),
                tcp::State::Closed | tcp::State::TimeWait => {
                    Poll::Ready(Err(io::Error::new(io::ErrorKind::ConnectionAborted, "连接被拒绝/重置")))
                }
                _ => {
                    sock.register_recv_waker(cx.waker());
                    sock.register_send_waker(cx.waker());
                    Poll::Pending
                }
            }
        })
        .await
    }

    /// 目标地址 (dst ip:port) —— 连接建立后调用。
    pub fn destination(&mut self) -> Option<(std::net::IpAddr, u16)> {
        if let Some(d) = self.dst {
            return Some(d);
        }
        let g = lock_inner(&self.stack.inner);
        if !g.sockets.iter().any(|(h, _)| h == self.handle) {
            return None;
        }
        let sock = g.sockets.get::<tcp::Socket>(self.handle);
        let d = sock.local_endpoint().map(|ep| (ep.addr.into(), ep.port));
        drop(g);
        self.dst = d;
        d
    }

    /// 关闭连接并释放 socket (幂等)。
    pub fn close(&self) {
        if !self.alive.swap(false, Ordering::Relaxed) {
            return;
        }
        {
            let mut g = lock_inner(&self.stack.inner);
            if g.sockets.iter().any(|(h, _)| h == self.handle) {
                g.sockets.get_mut::<tcp::Socket>(self.handle).close();
            }
        }
        self.stack.poll_now();
        {
            let mut g = lock_inner(&self.stack.inner);
            if g.sockets.iter().any(|(h, _)| h == self.handle) {
                g.sockets.remove(self.handle);
            }
            g.created_at.remove(&self.handle);
        }
    }
}

impl Drop for TunTcpStream {
    fn drop(&mut self) {
        self.close();
    }
}

impl AsyncRead for TunTcpStream {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let mut total = 0;
        let n = {
            let mut g = lock_inner(&self.stack.inner);
            if !g.sockets.iter().any(|(h, _)| h == self.handle) {
                return Poll::Ready(Ok(())); // Socket 已释放，视为 EOF
            }
            let sock = g.sockets.get_mut::<tcp::Socket>(self.handle);
            if sock.can_recv() {
                let mut err = None;
                while sock.can_recv() && buf.remaining() > 0 {
                    match sock.recv_slice(buf.initialize_unfilled()) {
                        Ok(n) if n > 0 => {
                            buf.advance(n);
                            total += n;
                        }
                        Ok(_) => break,
                        Err(e) => {
                            err = Some(io::Error::new(io::ErrorKind::ConnectionReset, format!("{e:?}")));
                            break;
                        }
                    }
                }
                if let Some(e) = err {
                    return Poll::Ready(Err(e));
                }
                Some(total)
            } else if !sock.may_recv() {
                // 对端已 FIN → EOF
                None
            } else {
                sock.register_recv_waker(cx.waker());
                return Poll::Pending;
            }
        };
        if let Some(n) = n {
            if n > 0 {
                self.stack.poll_now();
            }
        }
        Poll::Ready(Ok(()))
    }
}

impl AsyncWrite for TunTcpStream {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        data: &[u8],
    ) -> Poll<io::Result<usize>> {
        let n = {
            let mut g = lock_inner(&self.stack.inner);
            if !g.sockets.iter().any(|(h, _)| h == self.handle) {
                return Poll::Ready(Err(io::Error::from(io::ErrorKind::BrokenPipe)));
            }
            let sock = g.sockets.get_mut::<tcp::Socket>(self.handle);
            if !sock.may_send() {
                return Poll::Ready(Err(io::Error::from(io::ErrorKind::BrokenPipe)));
            }
            if !sock.can_send() {
                sock.register_send_waker(cx.waker());
                return Poll::Pending;
            }
            sock.send_slice(data)
                .map_err(|e| io::Error::new(io::ErrorKind::BrokenPipe, format!("{e:?}")))?
        };
        self.stack.poll_now();
        Poll::Ready(Ok(n))
    }

    fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        self.stack.poll_now();
        Poll::Ready(Ok(()))
    }

    fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        {
            let mut g = lock_inner(&self.stack.inner);
            if g.sockets.iter().any(|(h, _)| h == self.handle) {
                g.sockets.get_mut::<tcp::Socket>(self.handle).close();
            }
        }
        self.stack.poll_now();
        Poll::Ready(Ok(()))
    }
}

/// 建立经 Mirage 隧道的出站流 (domain 或裸 IP)。
async fn connect_tunnel(
    stack: &TunStack,
    dst: (std::net::IpAddr, u16),
    direct_domain: Option<String>,
) -> anyhow::Result<Tunnel> {
    use crate::proxy::outbound::OutboundNode;

    let target = if let Some(domain) = direct_domain {
        Address::Domain(domain, dst.1)
    } else {
        Address::Socket(std::net::SocketAddr::new(dst.0, dst.1))
    };

    // 取默认出站的 Mirage pool (与上游 handler.rs 的隧道建立路径一致: 目标头由我们发,
    // 以便拿到 Tunnel 的 reader/writer + 支持 close_notify)。
    let node = stack
        .engine()
        .outbounds
        .get(&stack.engine().default_tag())
        .ok_or_else(|| anyhow::anyhow!("默认出站不存在"))?;
    let leaf = node.resolve_leaf();
    let OutboundNode::Mirage { pool, .. } = &*leaf else {
        anyhow::bail!("默认出站不是 Mirage");
    };

    let mut tunnel = pool.get().await?;
    let hp = target.host_port();
    let tb = hp.as_bytes();
    let mut hdr = Vec::with_capacity(2 + tb.len());
    hdr.extend_from_slice(&(tb.len() as u16).to_be_bytes());
    hdr.extend_from_slice(tb);
    tunnel.writer.send_data(&hdr).await?;
    Ok(tunnel)
}

/// TCP relay 任务入口: 等 Established → 解析目标 → 建隧道 → 双向转发。
pub async fn relay_tcp(stack: Arc<TunStack>, handle: SocketHandle) {
    TCP_ACTIVE.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let _guard = TcpActiveGuard;
    let mut stream = match TunTcpStream::from_catcher(stack.clone(), handle).await {
        Ok(s) => s,
        Err(e) => {
            debug!("[TUN-TCP] 建连失败: {e}");
            return; // stream drop → socket 清理
        }
    };
    let Some(dst) = stream.destination() else {
        warn!("[TUN-TCP] 无法取目标地址, 丢弃连接");
        return;
    };
    debug!("[TUN-TCP] 新连接 → {}", format!("{}:{}", dst.0, dst.1));

    // 分流: fake-IP 反查域名 / 裸 IP 智能嗅探 (TLS SNI / HTTP Host)
    let mut direct_domain = stack.engine().fake_ip_reverse(&dst.0);
    let mut initial_payload: Vec<u8> = Vec::new();

    // 性能快速通道: 若该 IP 已经为直连 IP、国内 IP 或私有局域网 IP，无需消耗 CPU/网络嗅探，直接跳过快速直连
    let should_sniff = direct_domain.is_none()
        && !crate::direct::is_direct_ip(dst.0)
        && !crate::direct::is_cn_ip(dst.0)
        && !crate::direct::is_private_ip(dst.0);

    if should_sniff {
        let mut sniff_buf = [0u8; 2048];
        // 将超时从 150ms 压缩至 40ms (移动端 smoltcp 缓冲区读取为 0ms，非 HTTP/TLS 最多等待 40ms 放行)
        if let Ok(Ok(n)) = tokio::time::timeout(std::time::Duration::from_millis(40), stream.read(&mut sniff_buf)).await {
            if n > 0 {
                let sniffed = crate::tun::sniffer::Sniffer::sniff_tcp(&sniff_buf[..n]);
                if let Some(h) = sniffed.host {
                    debug!("[TUN-TCP] 智能嗅探提取域名 ({:?}): {} (目标: {}:{})", sniffed.protocol, h, dst.0, dst.1);
                    direct_domain = Some(h);
                }
                initial_payload.extend_from_slice(&sniff_buf[..n]);
            }
        }
    }

    let action = crate::direct::route_decision(direct_domain.as_deref(), Some(dst.0), Some(dst.1), Some("tcp"));

    if action == crate::direct::RuleAction::Block {
        let target_name = direct_domain.as_deref().map(|d| d.to_string()).unwrap_or_else(|| dst.0.to_string());
        let (cid, _, _, _) = crate::monitor::record_conn_start("TCP", &format!("{}:{}", target_name, dst.1), "规则拦截 (Block)");
        crate::monitor::record_conn_close(cid, 0, 0);
        stream.close();
        debug!("[TUN-TCP] 规则拦截: 阻断连接 → {}:{}", target_name, dst.1);
        return;
    }

    if action == crate::direct::RuleAction::Direct {
        relay_direct(stack.clone(), stream, dst, direct_domain.clone(), initial_payload).await;
        return;
    }

    relay_proxy(stack, stream, dst, direct_domain, initial_payload).await;
}

/// 代理路径: smoltcp socket ⇄ Mirage 加密隧道
async fn relay_proxy(
    stack: Arc<TunStack>,
    stream: TunTcpStream,
    dst: (std::net::IpAddr, u16),
    direct_domain: Option<String>,
    initial_payload: Vec<u8>,
) {
    let tunnel = match connect_tunnel(&stack, dst, direct_domain.clone()).await {
        Ok(t) => t,
        Err(e) => {
            debug!("[TUN-TCP] 隧道建立失败 ({}:{}): {e}", dst.0, dst.1);
            return;
        }
    };

    let target_name = if let Some(dom) = &direct_domain {
        format!("{}:{}", dom, dst.1)
    } else {
        format!("{}:{}", dst.0, dst.1)
    };
    let (cid, conn_up, conn_down, conn_abort) = crate::monitor::record_conn_start("TCP", &target_name, "隧道代理");

    // 拆成读写半程: upload (app→tunnel) / download (tunnel→app)
    let (mut tun_reader, mut tun_writer) = (tunnel.reader, tunnel.writer);
    let (mut local_rd, mut local_wr) = tokio::io::split(stream);

    // 如果嗅探期间预读了首包数据，优先推入隧道发送
    if !initial_payload.is_empty() {
        if tun_writer.send_data(&initial_payload).await.is_err() {
            crate::monitor::record_conn_close(cid, 0, 0);
            return;
        }
        conn_up.fetch_add(initial_payload.len() as u64, std::sync::atomic::Ordering::Relaxed);
    }

    let start_time = std::time::Instant::now();
    let dom_ref = direct_domain.as_deref();
    let dst_port = dst.1;
    let up_atomic = conn_up.clone();

    // 跨事务多请求复用判定与双向活跃时间戳 (HTTP/1.1 Keep-Alive / HTTP/2 多路复用)
    let request_count = std::sync::Arc::new(std::sync::atomic::AtomicU32::new(1));
    let server_has_downloaded = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let last_active = std::sync::Arc::new(std::sync::atomic::AtomicU64::new(crate::tun::adaptive_idle::unix_now_secs()));

    let req_counter_up = request_count.clone();
    let srv_flag_up = server_has_downloaded.clone();
    let last_act_up = last_active.clone();
    let upload = async move {
        let mut up_bytes: u64 = 0;
        let mut timed_out = false;
        let mut buf = [0u8; 65536];
        loop {
            let timeout_dur = crate::tun::adaptive_idle::compute_adaptive_timeout(
                dst_port,
                dom_ref,
                up_bytes > 0,
            );
            match tokio::time::timeout(timeout_dur, local_rd.read(&mut buf)).await {
                Ok(Ok(0)) => {
                    let _ = tun_writer.send_close_notify().await;
                    break;
                }
                Ok(Ok(n)) => {
                    if tun_writer.send_data(&buf[..n]).await.is_err() {
                        break;
                    }
                    up_bytes += n as u64;
                    up_atomic.fetch_add(n as u64, std::sync::atomic::Ordering::Relaxed);
                    last_act_up.store(crate::tun::adaptive_idle::unix_now_secs(), std::sync::atomic::Ordering::Relaxed);
                    // 若在接收服务端响应后，客户端再次发起上行写入，计入一次新的请求复用 (Request Cycle)
                    if srv_flag_up.swap(false, std::sync::atomic::Ordering::Relaxed) {
                        req_counter_up.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    }
                }
                Ok(Err(_)) => {
                    let _ = tun_writer.send_close_notify().await;
                    break;
                }
                Err(_) => {
                    let now = crate::tun::adaptive_idle::unix_now_secs();
                    let last = last_act_up.load(std::sync::atomic::Ordering::Relaxed);
                    if now.saturating_sub(last) < timeout_dur.as_secs() {
                        // 下行仍在活跃推流，上行继续等待，不中断连接
                        continue;
                    }
                    debug!("[TUN-TCP] 全双工空闲超时 ({}s), 优雅关闭", timeout_dur.as_secs());
                    let _ = tun_writer.send_close_notify().await;
                    timed_out = true;
                    break;
                }
            }
        }
        (up_bytes, timed_out)
    };

    let down_atomic = conn_down.clone();
    let srv_flag_down = server_has_downloaded.clone();
    let last_act_down = last_active.clone();
    let download = async move {
        let mut down_bytes: u64 = 0;
        let mut timed_out = false;
        loop {
            let timeout_dur = crate::tun::adaptive_idle::compute_adaptive_timeout(
                dst_port,
                dom_ref,
                down_bytes > 0,
            );
            match tokio::time::timeout(timeout_dur, tun_reader.recv_data_to(&mut local_wr)).await {
                Ok(Ok(Some(n))) => {
                    down_bytes += n as u64;
                    down_atomic.fetch_add(n as u64, std::sync::atomic::Ordering::Relaxed);
                    last_act_down.store(crate::tun::adaptive_idle::unix_now_secs(), std::sync::atomic::Ordering::Relaxed);
                    srv_flag_down.store(true, std::sync::atomic::Ordering::Relaxed);
                }
                Ok(Ok(None)) => break, // 对端正常 close_notify
                Ok(Err(_)) => break,
                Err(_) => {
                    let now = crate::tun::adaptive_idle::unix_now_secs();
                    let last = last_act_down.load(std::sync::atomic::Ordering::Relaxed);
                    if now.saturating_sub(last) < timeout_dur.as_secs() {
                        // 上行近期有活跃传输，下行继续等待
                        continue;
                    }
                    debug!("[TUN-TCP] 全双工空闲超时 ({}s), 优雅关闭", timeout_dur.as_secs());
                    timed_out = true;
                    break;
                }
            }
        }
        let _ = local_wr.shutdown().await;
        (down_bytes, timed_out)
    };

    tokio::pin!(upload);
    tokio::pin!(download);
    let mut up = 0u64;
    let mut down = 0u64;
    let close_reason;

    tokio::select! {
        _ = conn_abort.notified() => {
            tracing::info!("[TUN-TCP] 连接 #{} 被主动切断 (DELETE /connections/{})", cid, cid);
            close_reason = crate::tun::adaptive_idle::CloseReason::ClientClosed;
        }
        (u, u_timeout) = &mut upload => {
            up = u;
            if u_timeout {
                close_reason = crate::tun::adaptive_idle::CloseReason::IdleTimeout;
            } else {
                close_reason = crate::tun::adaptive_idle::CloseReason::ClientClosed;
            }
            if let Ok((d, _)) = tokio::time::timeout(std::time::Duration::from_secs(3), &mut download).await {
                down = d;
            }
        }
        (d, d_timeout) = &mut download => {
            down = d;
            if d_timeout {
                close_reason = crate::tun::adaptive_idle::CloseReason::IdleTimeout;
            } else {
                close_reason = crate::tun::adaptive_idle::CloseReason::ServerClosed;
            }
            if let Ok((u, _)) = tokio::time::timeout(std::time::Duration::from_secs(2), &mut upload).await {
                up = u;
            }
        }
    }
    crate::monitor::record_conn_close(cid, up, down);
    let duration_ms = start_time.elapsed().as_millis() as u64;
    let req_total = request_count.load(std::sync::atomic::Ordering::Relaxed);
    let is_reused = req_total >= 2;
    crate::tun::adaptive_idle::record_conn_metrics(
        direct_domain.as_deref(),
        up,
        down,
        duration_ms,
        close_reason,
        is_reused,
    );
    debug!(
        "[TUN-TCP] {}:{} 关闭 (↑{} ↓{}, 耗时{}ms, 原因:{:?}, 请求数:{}, 复用:{})",
        dst.0,
        dst.1,
        crate::tun::udp::human_bytes(up),
        crate::tun::udp::human_bytes(down),
        duration_ms,
        close_reason,
        req_total,
        is_reused
    );
}

#[allow(dead_code)]
fn _sock_buf_const() -> usize {
    SOCK_BUF
}

/// 直连路径: smoltcp socket ⇄ 真实 TCP socket (protect 绕过 TUN，带自动回退代理)。
async fn relay_direct(
    stack: Arc<TunStack>,
    stream: TunTcpStream,
    dst: (std::net::IpAddr, u16),
    direct_domain: Option<String>,
    initial_payload: Vec<u8>,
) {
    let engine = stack.engine();
    let is_fake = engine.is_fake_ip(&dst.0);

    // 如果目标是 Fake-IP，必须先解析出真实 IP (或局域网路由器 IP)
    let target_ip = if is_fake {
        if let Some(ref dom) = direct_domain {
            if let Some(real_ip) = crate::tun::dns::direct_dns_lookup(dom) {
                real_ip
            } else if let Some(real_v4) = crate::tun::dns::resolve_upstream(dom).await {
                std::net::IpAddr::V4(real_v4)
            } else if let Some(router_ip) = crate::direct::default_router_ip_for_domain(dom) {
                debug!("[TUN-TCP/direct] 局域网管理域名 [{}] 使用默认网关 IP: {}", dom, router_ip);
                router_ip
            } else {
                debug!("[TUN-TCP/direct] 直连域名 [{}] 真实解析超时，自动平滑回退走隧道代理", dom);
                return relay_proxy(stack, stream, dst, direct_domain, initial_payload).await;
            }
        } else {
            debug!("[TUN-TCP/direct] 目标为 Fake-IP ({}) 但无对应域名映射，无法直连", dst.0);
            return;
        }
    } else {
        dst.0
    };

    // 严密安全防护: 若 Fake-IP 域名解析出非国内 IP (如境外域名被规则误判或 DNS 污染)，自动回退隧道代理 (私有 IP 豁免)
    if is_fake && !crate::direct::is_cn_ip(target_ip) && !crate::direct::is_direct_ip(target_ip) && !crate::direct::is_private_ip(target_ip) {
        debug!("[TUN-TCP/direct] 域名 [{:?}] 解析为非国内 IP ({})，自动回退走隧道代理", direct_domain, target_ip);
        return relay_proxy(stack, stream, dst, direct_domain, initial_payload).await;
    }

    let target_display = if let Some(ref dom) = direct_domain {
        format!("{}:{}", dom, dst.1)
    } else {
        format!("{}:{}", target_ip, dst.1)
    };

    let (cid, conn_up, conn_down, conn_abort) = crate::monitor::record_conn_start("TCP", &target_display, "直连");
    TCP_ACTIVE.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let _guard = TcpActiveGuard;
    use std::os::unix::io::AsRawFd;
    let addr = std::net::SocketAddr::new(target_ip, dst.1);
    let sock = match addr {
        std::net::SocketAddr::V4(_) => tokio::net::TcpSocket::new_v4(),
        std::net::SocketAddr::V6(_) => tokio::net::TcpSocket::new_v6(),
    };
    let sock = match sock {
        Ok(s) => s,
        Err(e) => {
            crate::monitor::record_conn_close(cid, 0, 0);
            debug!("[TUN-TCP/direct] 建 socket 失败: {e}");
            return;
        }
    };
    // protect: 直连 socket 也要绕过 TUN (否则 0.0.0.0/0→tun0 环路)
    crate::protect::protect(sock.as_raw_fd());
    let mut remote = match tokio::time::timeout(
        std::time::Duration::from_secs(8),
        sock.connect(addr),
    ).await {
        Ok(Ok(s)) => s,
        Ok(Err(e)) => {
            crate::monitor::record_conn_close(cid, 0, 0);
            debug!("[TUN-TCP/direct] 直连 {addr} 失败: {e}，自动回退走隧道代理");
            return relay_proxy(stack, stream, dst, direct_domain, initial_payload).await;
        }
        Err(_) => {
            crate::monitor::record_conn_close(cid, 0, 0);
            debug!("[TUN-TCP/direct] 直连 {addr} 超时，自动回退走隧道代理");
            return relay_proxy(stack, stream, dst, direct_domain, initial_payload).await;
        }
    };
    let _ = remote.set_nodelay(true);

    // 如果嗅探期间读取了首包，先写入真实 socket
    if !initial_payload.is_empty() {
        if remote.write_all(&initial_payload).await.is_err() {
            crate::monitor::record_conn_close(cid, 0, 0);
            return;
        }
        conn_up.fetch_add(initial_payload.len() as u64, std::sync::atomic::Ordering::Relaxed);
        crate::monitor::add_up(initial_payload.len() as u64);
    }

    // 双向转发 (smoltcp 侧 TunTcpStream ↔ 真实 socket)
    let mut local = stream;
    let (mut lr, mut lw) = tokio::io::split(&mut local);
    let (mut rr, mut rw) = remote.split();
    let start_time = std::time::Instant::now();
    let dom_ref = direct_domain.as_deref();
    let dst_port = dst.1;
    let up_atomic = conn_up.clone();

    // 跨事务多请求复用判定与双向活跃时间戳 (HTTP/1.1 Keep-Alive / HTTP/2 多路复用)
    let request_count = std::sync::Arc::new(std::sync::atomic::AtomicU32::new(1));
    let server_has_downloaded = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let last_active = std::sync::Arc::new(std::sync::atomic::AtomicU64::new(crate::tun::adaptive_idle::unix_now_secs()));

    let req_counter_to = request_count.clone();
    let srv_flag_to = server_has_downloaded.clone();
    let last_act_to = last_active.clone();
    let to_tunnel = async move {
        let mut up_bytes: u64 = 0;
        let mut timed_out = false;
        let mut buf = [0u8; 65536];
        loop {
            let timeout_dur = crate::tun::adaptive_idle::compute_adaptive_timeout(
                dst_port,
                dom_ref,
                up_bytes > 0,
            );
            match tokio::time::timeout(timeout_dur, lr.read(&mut buf)).await {
                Ok(Ok(0)) => break,
                Ok(Ok(n)) => {
                    if rw.write_all(&buf[..n]).await.is_err() { break; }
                    up_bytes += n as u64;
                    up_atomic.fetch_add(n as u64, std::sync::atomic::Ordering::Relaxed);
                    crate::monitor::add_up(n as u64);
                    last_act_to.store(crate::tun::adaptive_idle::unix_now_secs(), std::sync::atomic::Ordering::Relaxed);
                    // 若在接收服务端响应后，客户端再次发起上行写入，计入一次新的请求复用 (Request Cycle)
                    if srv_flag_to.swap(false, std::sync::atomic::Ordering::Relaxed) {
                        req_counter_to.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    }
                }
                Ok(Err(_)) => break,
                Err(_) => {
                    let now = crate::tun::adaptive_idle::unix_now_secs();
                    let last = last_act_to.load(std::sync::atomic::Ordering::Relaxed);
                    if now.saturating_sub(last) < timeout_dur.as_secs() {
                        continue;
                    }
                    timed_out = true;
                    break;
                }
            }
        }
        (up_bytes, timed_out)
    };
    let down_atomic = conn_down.clone();
    let srv_flag_from = server_has_downloaded.clone();
    let last_act_from = last_active.clone();
    let from_tunnel = async move {
        let mut down_bytes: u64 = 0;
        let mut timed_out = false;
        let mut buf = [0u8; 65536];
        loop {
            let timeout_dur = crate::tun::adaptive_idle::compute_adaptive_timeout(
                dst_port,
                dom_ref,
                down_bytes > 0,
            );
            match tokio::time::timeout(timeout_dur, rr.read(&mut buf)).await {
                Ok(Ok(0)) => break,
                Ok(Ok(n)) => {
                    if lw.write_all(&buf[..n]).await.is_err() { break; }
                    down_bytes += n as u64;
                    down_atomic.fetch_add(n as u64, std::sync::atomic::Ordering::Relaxed);
                    crate::monitor::add_down(n as u64);
                    last_act_from.store(crate::tun::adaptive_idle::unix_now_secs(), std::sync::atomic::Ordering::Relaxed);
                    srv_flag_from.store(true, std::sync::atomic::Ordering::Relaxed);
                }
                Ok(Err(_)) => break,
                Err(_) => {
                    let now = crate::tun::adaptive_idle::unix_now_secs();
                    let last = last_act_from.load(std::sync::atomic::Ordering::Relaxed);
                    if now.saturating_sub(last) < timeout_dur.as_secs() {
                        continue;
                    }
                    timed_out = true;
                    break;
                }
            }
        }
        (down_bytes, timed_out)
    };
    tokio::pin!(to_tunnel);
    tokio::pin!(from_tunnel);
    let mut up: u64 = 0;
    let mut down: u64 = 0;
    let close_reason;

    tokio::select! {
        _ = conn_abort.notified() => {
            tracing::info!("[TUN-TCP/direct] 直连 #{} 被主动切断", cid);
            close_reason = crate::tun::adaptive_idle::CloseReason::ClientClosed;
        }
        (u, u_timeout) = &mut to_tunnel => {
            up = u;
            if u_timeout {
                close_reason = crate::tun::adaptive_idle::CloseReason::IdleTimeout;
            } else {
                close_reason = crate::tun::adaptive_idle::CloseReason::ClientClosed;
            }
            if let Ok((d, _)) = tokio::time::timeout(std::time::Duration::from_secs(3), &mut from_tunnel).await {
                down = d;
            }
        }
        (d, d_timeout) = &mut from_tunnel => {
            down = d;
            if d_timeout {
                close_reason = crate::tun::adaptive_idle::CloseReason::IdleTimeout;
            } else {
                close_reason = crate::tun::adaptive_idle::CloseReason::ServerClosed;
            }
            if let Ok((u, _)) = tokio::time::timeout(std::time::Duration::from_secs(2), &mut to_tunnel).await {
                up = u;
            }
        }
    }
    crate::monitor::record_conn_close(cid, up, down);
    let duration_ms = start_time.elapsed().as_millis() as u64;
    let req_total = request_count.load(std::sync::atomic::Ordering::Relaxed);
    let is_reused = req_total >= 2;
    crate::tun::adaptive_idle::record_conn_metrics(
        direct_domain.as_deref(),
        up,
        down,
        duration_ms,
        close_reason,
        is_reused,
    );
    debug!("[TUN-TCP/direct] {}:{} 直连关闭 (↑{} ↓{}, 耗时{}ms, 原因:{:?}, 请求数:{}, 复用:{})",
        dst.0, dst.1,
        crate::tun::udp::human_bytes(up), crate::tun::udp::human_bytes(down),
        duration_ms, close_reason, req_total, is_reused);
}
