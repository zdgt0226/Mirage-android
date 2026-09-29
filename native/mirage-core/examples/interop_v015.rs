//! 与上游 mirage-rs 服务端的协议互通冒烟测试 (开发用, 不进 release .so)。
//!
//! 走 mirage-core 真实客户端路径 (WarmPool → 伪装握手 → 派生会话密钥 → TIME_SYNC /
//! cipher agility), 经隧道连 echo 目标, 发数据并校验原样回显。包含:
//! 1. TCP 隧道普通下行回显与 App 实际热路径 `CryptoReader::recv_data_to(&mut writer)` 零拷贝读取校验;
//! 2. UDP Legacy (0x00 sentinel) ATYP=0x01 (IPv4 目标) 与 ATYP=0x03 (域名 localhost 目标) 回显校验;
//! 3. UDP mux 多路复用隧道回显校验。
//!
//! ### 用法 1: 本地起 `mirage lite-server` (轻量测试模式)
//! ```bash
//! # 准备本地 TCP echo (19000) 与 UDP echo (19001)
//! # lite-server pfs=true:
//! MIRAGE_SERVER=127.0.0.1 MIRAGE_PORT=18443 MIRAGE_PWD=pw MIRAGE_PFS=1 \
//!   MIRAGE_TARGET=127.0.0.1:19000 MIRAGE_UDP_TARGET=127.0.0.1:19001 cargo run --example interop_v015
//! # lite-server pfs=false:
//! MIRAGE_SERVER=127.0.0.1 MIRAGE_PORT=18444 MIRAGE_PWD=pw MIRAGE_PFS=0 \
//!   MIRAGE_TARGET=127.0.0.1:19000 MIRAGE_UDP_TARGET=127.0.0.1:19001 cargo run --example interop_v015
//! ```
//!
//! ### 用法 2: 本地起完整模式服务端 (`mirage server -c <cfg>`)
//! mirage-rs 的 lite-server 不支持 `cipher_agility` 与 `tls_padding` 开关。测试这两个高级特性需使用完整模式配置。
//!
//! ⚠️ 语义说明 (关于 `tls_padding` 与 `cipher_agility`):
//! - `cipher_agility`: 服务端开启后在 TIME_SYNC 下发 `proto_ver=0x02`，mirage-core 客户端会主动在加密信道内
//!   发送 `CIPHER_NEGO` 并等待服务端的 `CIPHER_ACK`，双方协商后均支持 AES 时平滑 rekey 到 AES-256-GCM。
//! - `tls_padding`: 服务端开启后对握手后的前几条加密记录追加 TLS 1.3 规范的尾部零填充整形。由于移动端
//!   `mirage-core` 的 `CryptoReader` (`process_plaintext_mobile`) 恒定具备剥尾零逻辑，因此即使移动端自身发端不开
//!   `tls_padding`（`CryptoWriter` 不填零），移动端接收服务端开启 `tls_padding` 的加密数据流仍可完全正常解密互通。
//!   服务端亦能正常接收来自客户端的不含填充的帧。
//!
//! 完整模式最小配置示例 (如 `interop_server_full.json`):
//! ```json
//! {
//!   "schema_version": 1,
//!   "log_level": "warn",
//!   "inbounds": [
//!     {
//!       "type": "mirage_server",
//!       "tag": "mirage-in",
//!       "listen": "127.0.0.1",
//!       "port": 18445,
//!       "password": "pw-interop",
//!       "camouflage_host": "www.apple.com",
//!       "pfs": true,
//!       "allow_local_targets": true
//!     }
//!   ],
//!   "outbounds": [
//!     {
//!       "type": "direct",
//!       "tag": "direct"
//!     }
//!   ],
//!   "routing": {
//!     "default_outbound": "direct",
//!     "rules": []
//!   },
//!   "tuning": {
//!     "cipher_agility": true,
//!     "tls_padding": true
//!   }
//! }
//! ```
//! 运行方式:
//! ```bash
//! mirage server -c interop_server_full.json
//! MIRAGE_SERVER=127.0.0.1 MIRAGE_PORT=18445 MIRAGE_PWD=pw-interop MIRAGE_PFS=1 \
//!   MIRAGE_TARGET=127.0.0.1:19000 MIRAGE_UDP_TARGET=127.0.0.1:19001 cargo run --example interop_v015
//! ```

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

        // TCP 下行 App 真实热路径: 用 CryptoReader::recv_data_to(&mut writer) 读取校验
        let payload_stream = format!("interop-v015-recv-data-to-pfs={}", cfg.pfs);
        tunnel.writer.send_data(payload_stream.as_bytes()).await?;
        let mut sink = std::io::Cursor::new(Vec::new());
        let n = tunnel.reader.recv_data_to(&mut sink).await?;
        anyhow::ensure!(
            n == Some(payload_stream.len()),
            "recv_data_to 写入长度不符: {:?}",
            n
        );
        let got_stream = sink.into_inner();
        anyhow::ensure!(
            got_stream == payload_stream.as_bytes(),
            "recv_data_to 回显不一致: {:?}",
            String::from_utf8_lossy(&got_stream)
        );
        eprintln!(
            "[interop] ✅ pfs={} TCP recv_data_to 回显一致 ({} B)",
            cfg.pfs,
            got_stream.len()
        );

        // 可选: UDP (MIRAGE_UDP_TARGET=ipv4:port 指向 UDP echo)。移动端两条路径都测:
        // ① Legacy 单流 (默认, tun/udp.rs: 首帧 0x00, 之后 [2B len][ATYP][ADDR][PORT][payload]);
        // ② mux (节点开 udp_mux 时)。
        if let Ok(udp_target) = std::env::var("MIRAGE_UDP_TARGET") {
            let sa: std::net::SocketAddrV4 = udp_target.parse()?;

            // ①.1 Legacy 单流 (ATYP=0x01 IPv4): 回包 ATYP 必须是 1 (IPv4) 且地址为目标 IP ——
            // 服务端双栈 socket 下 IPv4 回包来源为 ::ffff:a.b.c.d, 上游 #162 修复前会被错编成 ATYP=4 致客户端丢包。
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
                        acc.drain(..2 + flen);
                        break;
                    }
                }
                acc.extend_from_slice(&t.reader.recv_data().await?);
            }

            // ①.2 Legacy 单流 (ATYP=0x03 域名): 目标为域名 localhost:port, 服务端远程解析为 127.0.0.1 并回显
            let domain = b"localhost";
            let domain_payload = format!("interop-udp-domain-pfs={}", cfg.pfs);
            let dom_body_len = 1 + 1 + domain.len() + 2 + domain_payload.len();
            let mut f_dom = Vec::with_capacity(2 + dom_body_len);
            f_dom.extend_from_slice(&(dom_body_len as u16).to_be_bytes());
            f_dom.push(0x03);
            f_dom.push(domain.len() as u8);
            f_dom.extend_from_slice(domain);
            f_dom.extend_from_slice(&sa.port().to_be_bytes());
            f_dom.extend_from_slice(domain_payload.as_bytes());
            t.writer.send_data(&f_dom).await?;
            loop {
                if acc.len() >= 2 {
                    let flen = u16::from_be_bytes([acc[0], acc[1]]) as usize;
                    if acc.len() >= 2 + flen {
                        let frame = &acc[2..2 + flen];
                        anyhow::ensure!(
                            frame.first() == Some(&0x01),
                            "Legacy UDP (域名) 回包 ATYP 应为 1 (IPv4), 实为 {:?}",
                            frame.first()
                        );
                        anyhow::ensure!(frame.len() >= 7, "Legacy UDP 域名回包帧过短");
                        anyhow::ensure!(
                            &frame[7..] == domain_payload.as_bytes(),
                            "Legacy UDP (域名) 回显不一致: {:?}",
                            String::from_utf8_lossy(&frame[7..])
                        );
                        eprintln!(
                            "[interop] ✅ pfs={} UDP Legacy (ATYP=0x03 domain) 回显一致",
                            cfg.pfs
                        );
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
