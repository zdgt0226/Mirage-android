fn main() {
    let cases = [
        "mirage://0123456789abcdef0123456789abcdef@198.51.100.83:443/?sni=example.com",
        "mirage://pass@1.2.3.4:443?sni=www.apple.com",
        "mirage://pw@[2001:db8::1]:443?sni=x.com",
    ];
    for u in cases {
        match mirage_core::node_uri::NodeUri::parse(u) {
            Ok(n) => println!("OK  host={} port={} sni={}", n.host, n.port, n.sni),
            Err(e) => println!("ERR {e}"),
        }
    }
}
