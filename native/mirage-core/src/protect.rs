//! 隧道 socket 保护钩子。
//!
//! Android 的 VPNService 要求: 代理软件自己的出站 socket 必须 `VpnService.protect(fd)`,
//! 否则隧道流量会重新被路由进 TUN 造成环路。iOS 的 `NEPacketTunnelProvider` 无此要求
//! (隧道 socket 默认走真实网络)。
//!
//! 各平台包装层 (mirage-jni) 在启动时注册回调; 内核 (proxy::pool) 在每建一条隧道 socket
//! 后立即调用。未注册时静默跳过 (桌面端/Linux 无此概念)。

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::OnceLock;

/// 保护回调: 输入原始 socket fd, 返回 ()。
pub type ProtectFn = Box<dyn Fn(i32) + Send + Sync>;

static PROTECT: OnceLock<ProtectFn> = OnceLock::new();
static ACTIVE_NET_HANDLE: AtomicU64 = AtomicU64::new(0);

#[cfg(target_os = "android")]
type AndroidSetSockNetworkFn = unsafe extern "C" fn(u64, i32) -> i32;

#[cfg(target_os = "android")]
static SET_SOCK_NETWORK_FN: OnceLock<Option<AndroidSetSockNetworkFn>> = OnceLock::new();

#[cfg(target_os = "android")]
fn bind_socket_to_network(net_handle: u64, fd: i32) -> bool {
    let fn_opt = SET_SOCK_NETWORK_FN.get_or_init(|| {
        unsafe {
            let sym = libc::dlsym(libc::RTLD_DEFAULT, b"android_setsocknetwork\0".as_ptr() as *const _);
            if !sym.is_null() {
                return Some(std::mem::transmute::<*mut libc::c_void, AndroidSetSockNetworkFn>(sym));
            }
            let handle = libc::dlopen(b"libandroid.so\0".as_ptr() as *const _, libc::RTLD_NOW);
            if !handle.is_null() {
                let sym = libc::dlsym(handle, b"android_setsocknetwork\0".as_ptr() as *const _);
                if !sym.is_null() {
                    return Some(std::mem::transmute::<*mut libc::c_void, AndroidSetSockNetworkFn>(sym));
                }
            }
            None
        }
    });

    if let Some(f) = fn_opt {
        let ret = unsafe { f(net_handle, fd) };
        if ret != 0 {
            let errno = std::io::Error::last_os_error();
            tracing::warn!("[protect] android_setsocknetwork(handle={}, fd={}) 失败: {}", net_handle, fd, errno);
            return false;
        }
        true
    } else {
        false
    }
}

/// 记录当前底层活动物理网络的系统句柄 (来自 Network#getNetworkHandle)
pub fn set_active_network_handle(handle: u64) {
    ACTIVE_NET_HANDLE.store(handle, Ordering::Release);
}

/// 获取当前底层活动物理网络的系统句柄
pub fn get_active_network_handle() -> u64 {
    ACTIVE_NET_HANDLE.load(Ordering::Acquire)
}

/// 注册保护回调 (幂等, 重复注册忽略)。
pub fn set_protect_callback(f: ProtectFn) {
    let _ = PROTECT.set(f);
}

/// 清空 (测试用)。
pub fn clear_protect_callback() {
    // OnceLock 无法重置; 用哨兵函数替代 (见下)
    let _ = PROTECT.set(Box::new(|_| {}));
}

/// 是否已注册。
pub fn is_set() -> bool {
    PROTECT.get().is_some()
}

/// 保护一个 socket (未注册时无操作)。
pub fn protect(fd: i32) {
    // 1. 原生 NDK 物理网络显式绑定 (消除 Java 反射与 PFD 堆分配开销)
    #[cfg(target_os = "android")]
    {
        let handle = ACTIVE_NET_HANDLE.load(Ordering::Acquire);
        if handle != 0 {
            bind_socket_to_network(handle, fd);
        }
    }

    // 2. 调用系统 VpnService.protect(fd) 设置 SO_MARK 绕过 VPN
    if let Some(f) = PROTECT.get() {
        f(fd);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_active_network_handle() {
        assert_eq!(get_active_network_handle(), 0);
        set_active_network_handle(1234567890);
        assert_eq!(get_active_network_handle(), 1234567890);
        set_active_network_handle(0);
        assert_eq!(get_active_network_handle(), 0);
    }
}
