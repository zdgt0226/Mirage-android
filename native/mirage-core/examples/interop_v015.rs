//! 与上游 mirage-rs 服务端的协议互通冒烟测试 (开发用, 不进 release .so)。
//!
//! 走 mirage-core 真实客户端路径 (WarmPool → 伪装握手 → 派生会话密钥 → TIME_SYNC /
//! cipher agility), 经隧道连一个 echo 目标, 发数据并校验原样回显。用于 vendor 同步后
//! 验证与对应版本服务端逐字节兼容。
//!
//! 用法 (本地起 `mirage lite-server`, 配 allow_local_targets 以便连 127.0.0.1 echo):
//!   MIRAGE_SERVER=127.0.0.1 MIRAGE_PORT=18443 MIRAGE_PWD=pw MIRAGE_PFS=1 \
//!   MIRAGE_TARGET=127.0.0.1:19000 cargo run --example interop_v015
//! 成功退出码 0, 任一步失败退出码 1。

use std::sync::Arc;

use mirage_core::proxy::pool::{BrutalState, PoolConfig, WarmPool};

#[tokio::main]
async fn main() {
    let _ = tracing_subscriber::fmt()
        .with_max_level(tracing::Level::INFO)
        .try_init();

    let env = |k: &str| std::env::var(k).unwrap_or_else(|_| panic!("缺少环境变量 {k}"));
    let cfg = Arc::new(PoolConfig {
        server_host: env("MIRAGE_SERVER"),
        server_port: env("MIRAGE_PORT").parse().expect("MIRAGE_PORT"),
        password: env("MIRAGE_PWD"),
        camouflage_host: std::env::var("MIRAGE_SNI").unwrap_or_else(|_| "www.apple.com".into()),
        pool_size: 1,
        underlying: None,
        pfs: std::env::var("MIRAGE_PFS").is_ok_and(|v| v == "1"),
    });
    let target = env("MIRAGE_TARGET");
    let brutal = Arc::new(BrutalState {
        configured_rate: None,
        current_rate: Arc::new(std::sync::atomic::AtomicU64::new(0)),
        base_rtt: None,
        active_fds: Arc::new(std::sync::Mutex::new(std::collections::HashSet::new())),
    });

    let ok = tokio::time::timeout(std::time::Duration::from_secs(20), async {
        let pool = WarmPool::new(cfg.clone(), brutal);
        let mut tunnel = pool.get().await?;
        // 首帧 = [2B 目标长度][host:port], 与 tun/tcp.rs 一致。
        let tb = target.as_bytes();
        let mut hdr = Vec::with_capacity(2 + tb.len());
        hdr.extend_from_slice(&(tb.len() as u16).to_be_bytes());
        hdr.extend_from_slice(tb);
        tunnel.writer.send_data(&hdr).await?;
        let payload = format!("interop-v015-pfs={}", cfg.pfs);
        tunnel.writer.send_data(payload.as_bytes()).await?;
        let got = tunnel.reader.recv_data().await?;
        anyhow::ensure!(
            got == payload.as_bytes(),
            "回显不一致: {:?}",
            String::from_utf8_lossy(&got)
        );
        eprintln!(
            "[interop] ✅ pfs={} TCP 隧道回显一致 ({} B)",
            cfg.pfs,
            got.len()
        );

        // 可选: UDP (MIRAGE_UDP_TARGET=ipv4:port 指向 UDP echo)。移动端两条路径都测:
        // ① Legacy 单流 (默认, tun/udp.rs: 首帧 0x00, 之后 [2B len][ATYP][ADDR][PORT][payload]);
        // ② mux (节点开 udp_mux 时)。
        if let Ok(udp_target) = std::env::var("MIRAGE_UDP_TARGET") {
            let sa: std::net::SocketAddrV4 = udp_target.parse()?;

            // ① Legacy 单流: 回包 ATYP 必须是 1 (IPv4) 且地址为目标 IP —— 服务端双栈 socket 下
            // IPv4 回包来源为 ::ffff:a.b.c.d, 上游 #162 修复前会被错编成 ATYP=4 致客户端丢包。
            let mut t = pool.get().await?;
            t.writer.send_data(&[0x00]).await?;
            let legacy_payload = format!("interop-udp-legacy-pfs={}", cfg.pfs);
            let body_len = 1 + 4 + 2 + legacy_payload.len();
            let mut f = Vec::with_capacity(2 + body_len);
            f.extend_from_slice(&(body_len as u16).to_be_bytes());
            f.push(0x01);
            f.extend_from_slice(&sa.ip().octets());
            f.extend_from_slice(&sa.port().to_be_bytes());
            f.extend_from_slice(legacy_payload.as_bytes());
            t.writer.send_data(&f).await?;
            let mut acc: Vec<u8> = Vec::new();
            loop {
                if acc.len() >= 2 {
                    let flen = u16::from_be_bytes([acc[0], acc[1]]) as usize;
                    if acc.len() >= 2 + flen {
                        let frame = &acc[2..2 + flen];
                        anyhow::ensure!(
                            frame.first() == Some(&0x01),
                            "Legacy UDP 回包 ATYP 应为 1 (IPv4), 实为 {:?}",
                            frame.first()
                        );
                        anyhow::ensure!(frame.len() >= 7, "Legacy UDP 回包帧过短");
                        anyhow::ensure!(frame[1..5] == sa.ip().octets(), "回包源地址不符");
                        anyhow::ensure!(
                            &frame[7..] == legacy_payload.as_bytes(),
                            "Legacy UDP 回显不一致: {:?}",
                            String::from_utf8_lossy(&frame[7..])
                        );
                        eprintln!("[interop] ✅ pfs={} UDP Legacy 回显一致 (ATYP=1)", cfg.pfs);
                        break;
                    }
                }
                acc.extend_from_slice(&t.reader.recv_data().await?);
            }

            // ② mux
            let mut t = pool.get().await?;
            t.writer
                .send_data(&[mirage_core::proxy::udp_mux::MUX_SENTINEL])
                .await?;
            let udp_payload = format!("interop-udp-pfs={}", cfg.pfs);
            let frame = mirage_core::proxy::udp_mux::frame_mux_ipv4(
                7,
                sa.ip(),
                sa.port(),
                udp_payload.as_bytes(),
            )
            .ok_or_else(|| anyhow::anyhow!("frame_mux_ipv4 失败"))?;
            t.writer.send_data(&frame).await?;
            let mut acc: Vec<u8> = Vec::new();
            loop {
                if let Some((sid, body, used)) = mirage_core::proxy::udp_mux::parse_mux_frame(&acc)
                {
                    acc.drain(..used);
                    anyhow::ensure!(sid == 7, "回包 sid 不符: {sid}");
                    anyhow::ensure!(
                        body.ends_with(udp_payload.as_bytes()),
                        "UDP 回显不一致: {:?}",
                        String::from_utf8_lossy(&body)
                    );
                    eprintln!("[interop] ✅ pfs={} UDP mux 回显一致", cfg.pfs);
                    break;
                }
                acc.extend_from_slice(&t.reader.recv_data().await?);
            }
        }
        anyhow::Ok(())
    })
    .await;

    match ok {
        Ok(Ok(())) => std::process::exit(0),
        Ok(Err(e)) => {
            eprintln!("[interop] ❌ {e:#}");
            std::process::exit(1)
        }
        Err(_) => {
            eprintln!("[interop] ❌ 超时 (20s)");
            std::process::exit(1)
        }
    }
}
