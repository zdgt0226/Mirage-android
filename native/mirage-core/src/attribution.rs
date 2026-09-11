use std::net::IpAddr;
use std::sync::OnceLock;

pub type PackageResolver = Box<dyn Fn(u8, IpAddr, u16, IpAddr, u16) -> Option<String> + Send + Sync>;

static RESOLVER: OnceLock<PackageResolver> = OnceLock::new();

/// 注册平台级应用溯源解析器 (由 mirage-jni 挂载 Android JNI 实现)
pub fn set_package_resolver(resolver: PackageResolver) {
    let _ = RESOLVER.set(resolver);
}

/// 解析连接归属的应用包名 (protocol: 6=TCP, 17=UDP)
pub fn resolve_package(proto: u8, src_ip: IpAddr, src_port: u16, dst_ip: IpAddr, dst_port: u16) -> Option<String> {
    if let Some(f) = RESOLVER.get() {
        f(proto, src_ip, src_port, dst_ip, dst_port)
    } else {
        None
    }
}

/// 检查包名是否属于 IM、实时推送或音视频会议类高保活应用
#[inline]
pub fn is_im_or_push_package(pkg: &str) -> bool {
    let p = pkg.to_lowercase();
    p.contains("tencent.mm") // 微信
        || p.contains("tencent.mobileqq") // QQ
        || p.contains("tencent.tim") // TIM
        || p.contains("alibaba.android.rimet") // 钉钉
        || p.contains("ss.android.lark") // 飞书国内版
        || p.contains("larksuite") // 飞书海外版
        || p.contains("telegram") // Telegram (org.telegram.messenger, plus, nekogram)
        || p.contains("whatsapp") // WhatsApp
        || p.contains("discord") // Discord
        || p.contains("signal") // Signal
        || p.contains("line") // LINE
        || p.contains("slack") // Slack
        || p.contains("matrix") // Element / Matrix
        || p.contains("element")
        || p.contains("teams") // Microsoft Teams
        || p.contains("skype")
        || p.contains("google.android.talk") // Google Chat / Hangouts
        || p.contains("google.android.gms") // Google Play Services (GCM / FCM)
}
