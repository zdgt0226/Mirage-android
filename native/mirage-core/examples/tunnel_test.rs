use tokio::io::{AsyncReadExt, AsyncWriteExt};

#[tokio::main]
async fn main() {
    let _ = tracing_subscriber::fmt()
        .with_max_level(tracing::Level::DEBUG)
        .try_init();
    let server = std::env::var("MIRAGE_SERVER").unwrap_or("117.55.230.75".into());
    let port: u16 = std::env::var("MIRAGE_PORT").unwrap_or("8443".into()).parse().unwrap();
    let pwd = std::env::var("MIRAGE_PWD").unwrap_or("d029c98fd9fd3104cebf7ebb2ce632cd".into());
    let sni = std::env::var("MIRAGE_SNI").unwrap_or("speedtest.net".into());

    eprintln!("[test] now_sec on this device = {}", mirage_core::time_sync::now_sec());
    for (name, prof) in [
        ("OkHttp", mirage_core::crypto::tls_raw::Profile::OkHttp),
        ("Firefox", mirage_core::crypto::tls_raw::Profile::Firefox),
        ("Chromium", mirage_core::crypto::tls_raw::Profile::Chromium),
    ] {
        eprintln!("\n=== Testing profile: {name} ===");
        let addr = format!("{server}:{port}");
        let sock_res = tokio::time::timeout(std::time::Duration::from_secs(5), tokio::net::TcpStream::connect(&addr)).await;
        let mut sock = match sock_res {
            Ok(Ok(s)) => s,
            Ok(Err(e)) => { eprintln!("[{name}] Connect error: {e}"); continue; }
            Err(_) => { eprintln!("[{name}] Connect timeout"); continue; }
        };
        let token = mirage_core::crypto::hello_auth::make_session_token(&pwd);
        let (ch, _cr) = mirage_core::crypto::tls_raw::build_with_profile(prof, &sni, &token);
        eprintln!("[{name}] Sending ClientHello (len={})...", ch.len());
        if let Err(e) = sock.write_all(&ch).await {
            eprintln!("[{name}] write error: {e}");
            continue;
        }
        let mut buf = [0u8; 1024];
        match tokio::time::timeout(std::time::Duration::from_secs(5), sock.read(&mut buf)).await {
            Ok(Ok(0)) => eprintln!("[{name}] Server closed connection (EOF)"),
            Ok(Ok(n)) => eprintln!("[{name}] ✅ Received {n} bytes: {:02x?}", &buf[..n.min(32)]),
            Ok(Err(e)) => eprintln!("[{name}] read error: {e}"),
            Err(_) => eprintln!("[{name}] read timeout after 5s"),
        }
    }
}
