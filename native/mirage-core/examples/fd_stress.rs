//! FD 压力测试: 验证 WarmPool 是否泄漏文件描述符。
//! 连真实 Mirage 服务器, 模拟池常驻 + 并发取隧道/持有/关闭, 观察 /proc/self/fd 增长。
use mirage_core::engine::{Engine, NodeInfo};
use mirage_core::proxy::outbound::OutboundNode;
use std::time::Duration;

fn fd_count() -> usize {
    std::fs::read_dir("/proc/self/fd").map(|d| d.count()).unwrap_or(0)
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let node = NodeInfo {
        tag: "proxy".into(),
        server: std::env::var("MIRAGE_SERVER").unwrap_or("117.55.230.75".into()),
        server_port: std::env::var("MIRAGE_PORT").unwrap_or("8443".into()).parse().unwrap(),
        password: std::env::var("MIRAGE_PWD").unwrap_or("d029c98fd9fd3104cebf7ebb2ce632cd".into()),
        sni: std::env::var("MIRAGE_SNI").unwrap_or("speedtest.net".into()),
        pool_size: 8,
        pfs: false,
        udp_mux: true,
    };
    eprintln!("[fd_stress] 连 {}:{} 构建引擎...", node.server, node.server_port);
    let engine = Engine::new(&node)?;
    let outbound = engine.outbounds.get("proxy").expect("出站");
    let leaf = outbound.resolve_leaf();
    let OutboundNode::Mirage { pool, .. } = &*leaf else { return Ok(()) };

    println!("baseline fd = {}", fd_count());
    eprintln!("[fd_stress] baseline 完成, 观察池自稳...");

    // 阶段1: 池子自稳 (builder 循环补货, 不取) — 常驻 fd 应稳定在 target 附近
    for i in 0..8 {
        tokio::time::sleep(Duration::from_secs(5)).await;
        println!("stage1[{i}] idle fd = {}", fd_count());
    }

    // 阶段2: 模拟并发短连接 (取→持有→drop), 观察 fd 是否单调增长
    for round in 0..8 {
        let mut got = vec![];
        for _ in 0..12 {
            match tokio::time::timeout(Duration::from_secs(10), pool.get()).await {
                Ok(Ok(t)) => got.push(t),
                Err(_) => eprintln!("round {round}: pool.get 10s 超时"),
                Ok(Err(e)) => eprintln!("round {round}: pool.get err {e}"),
            }
        }
        println!("round {round}: 取到 {} 条, 持有中 fd = {}", got.len(), fd_count());
        tokio::time::sleep(Duration::from_secs(2)).await;
        drop(got);
        println!("round {round}: drop 后 fd = {}", fd_count());
        tokio::time::sleep(Duration::from_secs(5)).await; // 等池补货
    }

    // 阶段3: 长时间 idle 观察 (Manager 应清理 max_age 过期隧道)
    for i in 0..6 {
        tokio::time::sleep(Duration::from_secs(5)).await;
        println!("stage3[{i}] idle fd = {}", fd_count());
    }
    eprintln!("[fd_stress] 完成");
    Ok(())
}
