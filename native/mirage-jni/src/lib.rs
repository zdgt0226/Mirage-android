//! Mirage-Android JNI 包装层。
//!
//! Kotlin 侧通过 `System.loadLibrary("mirage_jni")` 加载, 调用:
//! ```kotlin
//! object MirageCore {
//!     external fun start(tunFd: Int, server: String, port: Int, password: String,
//!                        sni: String, poolSize: Int, pfs: Boolean): Int
//!     external fun stop()
//!     external fun isRunning(): Boolean
//!     external fun isHealthy(): Boolean
//!     external fun latencyMs(): Long
//!     external fun setLogCallback(cb: (String) -> Unit)
//! }
//! ```
//!

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, Once};

use mirage_core::engine::{Engine, NodeInfo};
use mirage_core::tun::{TunConfig, TunStack};
use mirage_core::node_uri::NodeUri;

#[global_allocator]
static GLOBAL: mimalloc::MiMalloc = mimalloc::MiMalloc;

// ── 全局运行时状态 ──────────────────────────────────────────────────────────

static STARTED: AtomicBool = AtomicBool::new(false);
/// 引擎与 TUN 栈句柄 (stop 时置空)。
static RUNTIME: Mutex<Option<Arc<RunState>>> = Mutex::new(None);
static CURRENT_POOL_SIZE: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(16);
static CURRENT_NODE_URI: Mutex<Option<String>> = Mutex::new(None);

struct RunState {
    engine: Arc<Engine>,
    stack: Arc<TunStack>,
    /// tokio runtime 的保活句柄 (stop 时 drop)。
    rt: Arc<tokio::runtime::Runtime>,
}


// ── 工具 ───────────────────────────────────────────────────────────────────

// ── JNI 导出 ───────────────────────────────────────────────────────────────

use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::util::SubscriberInitExt;
use tracing_subscriber::reload;
use tracing_subscriber::EnvFilter;

type ReloadHandle = reload::Handle<EnvFilter, tracing_subscriber::Registry>;
static RELOAD_HANDLE: Mutex<Option<ReloadHandle>> = Mutex::new(None);

/// 把 tracing 输出同时写进 App 日志面板 (monitor::global_logger) 与 stderr。
#[derive(Clone)]
struct PanelWriter;
impl<'a> tracing_subscriber::fmt::MakeWriter<'a> for PanelWriter {
    type Writer = PanelWriter;
    fn make_writer(&'a self) -> Self::Writer {
        PanelWriter
    }
}
impl std::io::Write for PanelWriter {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        let s = String::from_utf8_lossy(buf);
        let _ = mirage_core::monitor::recent_logs_push(&s);
        std::io::stderr().write_all(buf)?;
        Ok(buf.len())
    }
    fn flush(&mut self) -> std::io::Result<()> {
        std::io::stderr().flush()
    }
}

/// 提升进程文件描述符上限 (治本修复 too many open files)。
///
/// Android 应用进程默认 RLIMIT_NOFILE 常为 256~1024。代理场景 fd 峰值 = 隧道池
/// (常驻 + on-demand 并发拨号 ~24) + 活跃隧道/直连连接 + UDP 流, 多 App 并发时
/// 易撞上限 → 一切新 socket 创建失败 (EMFILE) → 隧道/直连/DNS 查询全挂。
/// 应用进程可将 soft limit 提升到 hard limit 内 (无需 root)。
fn raise_nofile_limit() {
    #[cfg(unix)]
    unsafe {
        let mut lim: libc::rlimit = std::mem::zeroed();
        if libc::getrlimit(libc::RLIMIT_NOFILE, &mut lim) == 0 {
            // cur = max(当前, 8192), 但不越过系统 hard limit
            let cur = lim.rlim_cur.max(8192).min(lim.rlim_max);
            if cur != lim.rlim_cur {
                let new = libc::rlimit { rlim_cur: cur, rlim_max: lim.rlim_max };
                if libc::setrlimit(libc::RLIMIT_NOFILE, &new) == 0 {
                    eprintln!("[mirage-jni] RLIMIT_NOFILE: {} -> {}", lim.rlim_cur, cur);
                }
            }
        }
    }
}

/// 初始化日志系统 (幂等)。
fn init_logging() {
    static LOG_INIT: Once = Once::new();
    LOG_INIT.call_once(|| {
        raise_nofile_limit();
        let default_filter = EnvFilter::try_from_default_env()
            .unwrap_or_else(|_| "mirage_core=debug,mirage_jni=debug".into());
        let (filter_layer, reload_handle) = reload::Layer::new(default_filter);
        if let Ok(mut lock) = RELOAD_HANDLE.lock() {
            *lock = Some(reload_handle);
        }

        let _ = tracing_subscriber::registry()
            .with(filter_layer)
            .with(
                tracing_subscriber::fmt::layer()
                    .with_ansi(false)
                    .with_writer(PanelWriter),
            )
            .try_init();

        // panic hook: Rust panic 会 abort 进程 (闪退), 至少把堆栈写进日志便于定位
        std::panic::set_hook(Box::new(|info| {
            eprintln!("[mirage-jni] PANIC: {info}");
            let bt = std::backtrace::Backtrace::force_capture();
            eprintln!("[mirage-jni] stack:\n{bt}");
        }));
    });
}

pub fn update_log_level(level: &str) -> bool {
    init_logging();
    let filter_str = match level.to_ascii_lowercase().as_str() {
        "trace" => "mirage_core=trace,mirage_jni=trace",
        "debug" => "mirage_core=debug,mirage_jni=debug",
        "warn" => "mirage_core=warn,mirage_jni=warn",
        "error" => "mirage_core=error,mirage_jni=error",
        _ => "mirage_core=info,mirage_jni=info",
    };
    if let Ok(lock) = RELOAD_HANDLE.lock() {
        if let Some(handle) = lock.as_ref() {
            if let Ok(new_filter) = EnvFilter::try_new(filter_str) {
                let ok = handle.reload(new_filter).is_ok();
                tracing::info!("[mirage-jni] 日志过滤级别已切换为: {level} (filter: {filter_str})");
                return ok;
            }
        }
    }
    false
}

/// `boolean setLogLevel(String level)` — 动态热切换 Rust 日志级别 (trace/debug/info/warn/error)。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_setLogLevel(
    mut env: JNIEnv,
    _class: JClass,
    level: JString,
) -> jboolean {
    let level_str: String = match env.get_string(&level) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    if update_log_level(&level_str) {
        1
    } else {
        0
    }
}

/// `int start(int tunFd, String uri, int poolSize, int mtu)` — uri 为 `mirage://密码@host:port?sni=...`。
///
/// 返回 0 = 成功, 负数 = 错误码。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_start(
    mut env: JNIEnv,
    _class: JClass,
    tun_fd: jint,
    uri: JString,
    pool_size: jint,
    mtu: jint,
) -> jint {
    init_logging();
    {
        let has_runtime = RUNTIME.lock().unwrap_or_else(|e| e.into_inner()).is_some();
        if STARTED.load(Ordering::SeqCst) && has_runtime {
            // 幂等: 已在跑, 返回 0 (Kotlin 应先 stop)
            return 0;
        }
    }
    STARTED.store(true, Ordering::SeqCst);

    let uri_str: String = match env.get_string(&uri) {
        Ok(s) => s.into(),
        Err(e) => {
            tracing::error!("读取 URI 失败: {e}");
            STARTED.store(false, Ordering::SeqCst);
            return -1;
        }
    };
    let node_uri = match NodeUri::parse(&uri_str) {
        Ok(u) => u,
        Err(e) => {
            tracing::error!("节点 URI 解析失败: {e}");
            STARTED.store(false, Ordering::SeqCst);
            return -1;
        }
    };
    let mut node = NodeInfo::from_node_uri(&node_uri);
    if pool_size > 0 {
        CURRENT_POOL_SIZE.store(pool_size as usize, Ordering::Relaxed);
        node.pool_size = pool_size as usize;
    } else {
        node.pool_size = CURRENT_POOL_SIZE.load(Ordering::Relaxed);
    }
    *CURRENT_NODE_URI.lock().unwrap_or_else(|e| e.into_inner()) = Some(uri_str);

    // 注册 protect 回调: **同步 JNI 调用** Kotlin 侧 `MirageNative.protectFd(fd)`。
    // 必须在 socket connect 之前完成 (SO_MARK 影响路由), 轮询队列来不及。
    let vm = match env.get_java_vm() {
        Ok(v) => v,
        Err(e) => {
            tracing::error!("获取 JavaVM 失败: {e}");
            STARTED.store(false, Ordering::SeqCst);
            return -5;
        }
    };
    let vm = std::sync::Arc::new(vm);
    let class = match env.find_class("com/mirage/android/core/MirageNative") {
        Ok(c) => c,
        Err(e) => {
            tracing::error!("查找 MirageNative 类失败: {e}");
            STARTED.store(false, Ordering::SeqCst);
            return -6;
        }
    };
    let class_ref = match env.new_global_ref(&class) {
        Ok(r) => r,
        Err(e) => {
            tracing::error!("创建类全局引用失败: {e}");
            STARTED.store(false, Ordering::SeqCst);
            return -7;
        }
    };
    let vm_for_protect = std::sync::Arc::clone(&vm);
    let class_for_protect = class_ref.clone();
    mirage_core::protect::set_protect_callback(Box::new(move |fd| {
        use jni::objects::JValue;
        // 从 tokio worker 线程 attach JVM (Daemon 模式, 避免反复分配/销毁 Java Thread 对象)
        let r = vm_for_protect.attach_current_thread_as_daemon().and_then(|mut env| {
            // jni 0.21: call_static_method(class, name, sig, args)
            env.call_static_method(
                &class_for_protect,
                "protectFd",
                "(I)V",
                &[JValue::Int(fd)],
            )
        });
        if let Err(e) = r {
            eprintln!("[mirage-jni] protect({fd}) 失败: {e}");
        }
    }));

    // 专用 tokio runtime (独立线程, 不占主线程)。必须先建 runtime 再在其内部
    // 调 Engine::new —— WarmPool::new 会 tokio::spawn 预热任务, 无 runtime 上下文
    // 直接 panic (JNI 边界 panic = abort = App 闪退, 真机实测踩坑)。
    let rt = match tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .thread_name("mirage-core")
        .build()
    {
        Ok(r) => Arc::new(r),
        Err(e) => {
            tracing::error!("tokio runtime 创建失败: {e}");
            STARTED.store(false, Ordering::SeqCst);
            return -4;
        }
    };

    let rt2 = rt.clone();
    let result = rt2.block_on(async move {
        // Engine::new + TunStack::start 都在 runtime 内执行
        let engine = match Engine::new(&node) {
            Ok(e) => e,
            Err(e) => {
                tracing::error!("引擎初始化失败: {e}");
                return Err(e);
            }
        };
        let mut cfg = TunConfig::default();
        if mtu >= 1280 && mtu <= 1500 {
            cfg.mtu = mtu as usize;
        }
        let stack = TunStack::start(engine.clone(), cfg, tun_fd as i32).await?;
        Ok((engine, stack))
    });

    let (engine, stack) = match result {
        Ok(v) => v,
        Err(e) => {
            tracing::error!("引擎/TUN 启动失败: {e}");
            STARTED.store(false, Ordering::SeqCst);
            return -3;
        }
    };

    *RUNTIME.lock().unwrap_or_else(|e| e.into_inner()) = Some(Arc::new(RunState {
        engine,
        stack,
        rt,
    }));
    tracing::info!("MirageCore 已启动");
    0
}

/// `void stop()`
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_stop(
    _env: JNIEnv,
    _class: JClass,
) {
    let state = RUNTIME.lock().unwrap_or_else(|e| e.into_inner()).take();
    if let Some(state) = state {
        state.engine.reset_dns_and_fake_ip();
        state.stack.stop();
        drop(state);
    }
    mirage_core::tun::dns::clear_direct_cache();
    STARTED.store(false, Ordering::SeqCst);
    tracing::info!("MirageCore 已停止");
}

/// `boolean isRunning()`
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_isRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    STARTED.load(Ordering::SeqCst) as jboolean
}

/// `boolean isHealthy()`
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_isHealthy(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let guard = RUNTIME.lock().unwrap_or_else(|e| e.into_inner());
    match guard.as_ref() {
        Some(s) => s.engine.is_healthy() as jboolean,
        None => 0,
    }
}

/// `boolean flushPool()` — 移动端切网 (Wi-Fi <-> 4G/5G) 或唤醒时清空已失效的空闲隧道。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_flushPool(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let guard = RUNTIME.lock().unwrap_or_else(|e| e.into_inner());
    let Some(state) = guard.as_ref() else { return 0 };
    state.stack.flush_pool();
    1
}

/// `long latencyMs()` — RTT 毫秒, -1 = 未知
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_latencyMs(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let guard = RUNTIME.lock().unwrap_or_else(|e| e.into_inner());
    match guard.as_ref() {
        Some(s) => s.engine.latency_ms().map(|v| v as jlong).unwrap_or(-1),
        None => -1,
    }
}

/// `String[] recentLogs()` — 最近日志 (Kotlin 日志面板)
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_recentLogs(
    mut env: JNIEnv,
    _class: JClass,
) -> jni::sys::jobjectArray {
    let logs = mirage_core::monitor::recent_logs();
    let arr = match env.new_object_array(logs.len() as i32, "java/lang/String", JObject::null()) {
        Ok(a) => a,
        Err(_) => return jni::sys::jobjectArray::default(),
    };
    if arr.is_null() {
        return arr.into_raw();
    }
    for (i, l) in logs.iter().enumerate() {
        let s = env.new_string(l).unwrap_or_else(|_| JString::from(JObject::null()));
        if !s.is_null() {
            let _ = env.set_object_array_element(&arr, i as i32, s);
        }
    }
    arr.into_raw()
}

/// `String getDiagnosticSnapshotJson()` — 获取内核诊断快照 JSON (包含流量、活跃流、日志丢包、版本等)。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_getDiagnosticSnapshotJson(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let json = mirage_core::monitor::get_diagnostic_snapshot_json();
    env.new_string(json).unwrap_or_else(|_| JString::from(JObject::null())).into_raw()
}

/// `boolean clearNativeLogs()` — 清空 Rust 内核内存日志。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_clearNativeLogs(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    mirage_core::monitor::clear_logs();
    1
}
/// tcp_conns, udp_flows, dns_queries] (速率单位 B/s)。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_getStats(
    env: JNIEnv,
    _class: JClass,
) -> jni::sys::jdoubleArray {
    let (up, down, up_rate, down_rate) = mirage_core::monitor::sample();
    let tcp = mirage_core::tun::tcp::TCP_ACTIVE.load(std::sync::atomic::Ordering::Relaxed) as f64;
    let udp = mirage_core::tun::udp::flow_count_global() as f64;
    let dns = mirage_core::tun::dns::DNS_QUERIES.load(std::sync::atomic::Ordering::Relaxed) as f64;
    let data = [up as f64, down as f64, up_rate, down_rate, tcp, udp, dns];
    match env.new_double_array(data.len() as i32) {
        Ok(arr) => {
            let _ = env.set_double_array_region(&arr, 0, &data);
            arr.into_raw()
        }
        Err(_) => jni::sys::jdoubleArray::default(),
    }
}

/// `boolean setNode(String uri)` — 运行时热切换节点 (重建引擎并替换, 无需断开)。
/// 现有隧道保留到自然断开, 新连接/新流走新节点。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_setNode(
    mut env: JNIEnv,
    _class: JClass,
    uri: JString,
) -> jboolean {
    let uri_str: String = match env.get_string(&uri) {
        Ok(x) => x.into(),
        Err(_) => return 0,
    };
    let node_uri = match mirage_core::node_uri::NodeUri::parse(&uri_str) {
        Ok(u) => u,
        Err(_) => return 0,
    };
    let mut node = mirage_core::engine::NodeInfo::from_node_uri(&node_uri);
    node.pool_size = CURRENT_POOL_SIZE.load(Ordering::Relaxed);
    *CURRENT_NODE_URI.lock().unwrap_or_else(|e| e.into_inner()) = Some(uri_str);

    // 修复 M4: 在锁内仅获取 Arc 引用并立即释放全局 RUNTIME 锁，
    // 避免在持有全局锁期间执行长时间的 block_on(Engine::new) 导致 UI 进程 getStats/isHealthy 被阻塞
    let (rt, stack) = {
        let guard = RUNTIME.lock().unwrap_or_else(|e| e.into_inner());
        let Some(state) = guard.as_ref() else { return 0 };
        (state.rt.clone(), state.stack.clone())
    };

    let result = rt.block_on(async move {
        mirage_core::engine::Engine::new(&node).map_err(|e| e.to_string())
    });
    match result {
        Ok(new_engine) => {
            // 同步热替换引擎
            stack.swap_engine(new_engine);
            1
        }
        Err(e) => {
            tracing::error!("setNode 引擎重建失败: {e}");
            0
        }
    }
}

/// `boolean setPoolSize(int poolSize)` — 动态热更新当前运行中的连接池容量。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_setPoolSize(
    _env: JNIEnv,
    _class: JClass,
    pool_size: jint,
) -> jboolean {
    if pool_size <= 0 {
        return 0;
    }
    let size = pool_size as usize;
    CURRENT_POOL_SIZE.store(size, Ordering::Relaxed);

    let guard = RUNTIME.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(state) = guard.as_ref() {
        state.stack.engine().set_pool_size(size);
        tracing::info!("MirageCore 已无缝热更新连接池容量为: {size}");
    }
    1
}

/// `boolean clearDnsCache()` — 清空并重置 Fake-IP 映射和直连 DNS 缓存。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_clearDnsCache(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let guard = RUNTIME.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(state) = guard.as_ref() {
        state.stack.engine().reset_dns_and_fake_ip();
    }
    1
}

/// `int getPoolSize()` — 获取当前内核连接池容量。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_getPoolSize(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    CURRENT_POOL_SIZE.load(Ordering::Relaxed) as jint
}

/// `long testNode(String uri, int timeoutMs)` — 完整协议握手测活。
/// 返回 RTT 毫秒; -1 = 节点不可用/握手失败。供自动节点选择用。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_testNode(
    mut env: JNIEnv,
    _class: JClass,
    uri: JString,
    timeout_ms: jint,
) -> jlong {
    use mirage_core::engine::NodeInfo;
    let uri_str: String = match env.get_string(&uri) {
        Ok(x) => x.into(),
        Err(_) => return -1,
    };
    let node_uri = match mirage_core::node_uri::NodeUri::parse(&uri_str) {
        Ok(u) => u,
        Err(_) => return -1,
    };
    let node = NodeInfo::from_node_uri(&node_uri);

    // 临时 runtime (测活在连接前, 无全局 runtime)
    let rt = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
        Ok(r) => r,
        Err(_) => return -1,
    };
    let timeout = std::time::Duration::from_millis(timeout_ms.max(1000) as u64);
    let result = rt.block_on(async move {
        let engine = match mirage_core::engine::Engine::new(&node) {
            Ok(e) => e,
            Err(_) => return -1i64,
        };
        let outbound = match engine.outbounds.get(engine.default_tag()) {
            Some(o) => o.clone(),
            None => return -1,
        };
        let leaf = outbound.resolve_leaf();
        let mirage_core::proxy::outbound::OutboundNode::Mirage { pool, .. } = &*leaf else {
            return -1;
        };
        let t0 = std::time::Instant::now();
        let r = match tokio::time::timeout(timeout, pool.get()).await {
            Ok(Ok(_)) => t0.elapsed().as_millis() as i64,
            _ => -1,
        };
        engine.shutdown();
        r
    });
    result
}

/// `String[] getBuiltinDomains()` — 内置国内域名列表 (规则界面展示)。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_getBuiltinDomains(
    mut env: JNIEnv,
    _class: JClass,
) -> jni::sys::jobjectArray {
    let list = mirage_core::direct::builtin_domains();
    let arr = match env.new_object_array(list.len() as i32, "java/lang/String", JObject::null()) {
        Ok(a) => a,
        Err(_) => return jni::sys::jobjectArray::default(),
    };
    for (i, d) in list.iter().enumerate() {
        if let Ok(s) = env.new_string(d) {
            let _ = env.set_object_array_element(&arr, i as i32, s);
        }
    }
    arr.into_raw()
}

/// `long getBuiltinIpCount()` — 内置中国 IP 段数量。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_getBuiltinIpCount(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    mirage_core::direct::builtin_ip_count() as jlong
}

/// `String getRuleHits()` — 规则命中统计 JSON。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_getRuleHits(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let s = mirage_core::direct::get_rule_hits();
    env.new_string(s).unwrap_or_else(|_| JString::from(JObject::null())).into_raw()
}

/// `boolean resetRuleHits()` — 清空规则命中统计。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_resetRuleHits(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    mirage_core::direct::reset_rule_hits();
    1
}

/// `boolean setRules(String json)` — 设置自定义分流规则 (App 启动/改规则时调用)。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_setRules(
    mut env: JNIEnv,
    _class: JClass,
    json: JString,
) -> jboolean {
    let json_str: String = match env.get_string(&json) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    if mirage_core::direct::set_custom_rules(&json_str) {
        1
    } else {
        0
    }
}

/// `String version()`
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_version(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let v = format!(
        "mirage-core {} / vendored {}",
        env!("CARGO_PKG_VERSION"),
        mirage_core::PROTOCOL_SYNC.lines().nth(2).unwrap_or("")
    );
    env.new_string(v).unwrap_or_else(|_| JString::from(JObject::null())).into_raw()
}

/// `String getConnectionsJson()` — 获取最近/活跃连接信息列表 (JSON 字符串)。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_getConnectionsJson(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let json = mirage_core::monitor::get_connections_json();
    env.new_string(json).unwrap_or_else(|_| JString::from(JObject::null())).into_raw()
}

/// `String getTrafficProfilesJson()` — 获取已学得的常用域名画像与时延设定记录 (JSON 字符串)。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_getTrafficProfilesJson(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let json = mirage_core::tun::adaptive_idle::get_learned_profiles_json();
    env.new_string(json).unwrap_or_else(|_| JString::from(JObject::null())).into_raw()
}

/// `boolean saveTrafficProfiles(String filePath)` — 将当前学得的画像持久化到指定文件。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_saveTrafficProfiles(
    mut env: JNIEnv,
    _class: JClass,
    file_path: JString,
) -> jboolean {
    let path_str: String = match env.get_string(&file_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    if mirage_core::tun::adaptive_idle::save_profiles_to_disk(&path_str).is_ok() {
        1
    } else {
        0
    }
}

/// `int loadTrafficProfiles(String filePath)` — 从指定文件加载历史流量画像。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_loadTrafficProfiles(
    mut env: JNIEnv,
    _class: JClass,
    file_path: JString,
) -> jint {
    let path_str: String = match env.get_string(&file_path) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    mirage_core::tun::adaptive_idle::load_profiles_from_disk(&path_str).unwrap_or(0) as jint
}

/// `boolean setBlockQuic(boolean block)` — 设置是否全局拦截 QUIC (UDP 443)。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_setBlockQuic(
    _env: JNIEnv,
    _class: JClass,
    block: jboolean,
) -> jboolean {
    mirage_core::direct::set_block_quic(block != 0);
    1
}

/// `boolean isBlockQuic()` — 查询当前是否开启 QUIC 拦截。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_isBlockQuic(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if mirage_core::direct::is_block_quic() {
        1
    } else {
        0
    }
}

/// `boolean setDnsServers(String directDns, String remoteDns)` — 设置国内与国外 DNS 服务器地址。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_setDnsServers(
    mut env: JNIEnv,
    _class: JClass,
    direct_dns: JString,
    remote_dns: JString,
) -> jboolean {
    let direct_str: String = match env.get_string(&direct_dns) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let remote_str: String = match env.get_string(&remote_dns) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    if let Ok(ip) = direct_str.trim().parse::<std::net::Ipv4Addr>() {
        mirage_core::tun::dns::set_direct_dns(ip);
    }
    if let Ok(ip) = remote_str.trim().parse::<std::net::IpAddr>() {
        mirage_core::tun::dns::set_remote_dns(ip);
    }
    1
}

/// `String getDirectDns()` — 获取当前国内直连 DNS 地址。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_getDirectDns(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let ip = mirage_core::tun::dns::get_direct_dns().to_string();
    env.new_string(ip).unwrap_or_else(|_| JString::from(JObject::null())).into_raw()
}

/// `String getRemoteDns()` — 获取当前国外远程 DNS 地址。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_getRemoteDns(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let ip = mirage_core::tun::dns::get_remote_dns().to_string();
    env.new_string(ip).unwrap_or_else(|_| JString::from(JObject::null())).into_raw()
}

/// `String loadGeoFiles(String geositePath, String geoipPath)` — 加载/重新加载 Geo 文件。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_loadGeoFiles(
    mut env: JNIEnv,
    _class: JClass,
    geosite_path: JString,
    geoip_path: JString,
) -> jstring {
    let site_p: String = env.get_string(&geosite_path).map(|s| s.into()).unwrap_or_default();
    let ip_p: String = env.get_string(&geoip_path).map(|s| s.into()).unwrap_or_default();

    let (site_count, ip_count) = mirage_core::geo::load_geo_files(&site_p, &ip_p);
    let result = serde_json::json!({
        "status": "ok",
        "geosite_tags": site_count,
        "geoip_codes": ip_count,
        "geosite_path": site_p,
        "geoip_path": ip_p,
    }).to_string();

    env.new_string(result).unwrap_or_else(|_| JString::from(JObject::null())).into_raw()
}

/// `String getGeoTags()` — 获取当前已加载的所有 GeoSite tags 和 GeoIP codes。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_getGeoTags(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let json = mirage_core::geo::get_geo_tags_json();
    env.new_string(json).unwrap_or_else(|_| JString::from(JObject::null())).into_raw()
}

/// `String getGeoTagsDetail()` — 获取包含详细条目数量的 GeoSite 和 GeoIP 列表 (供 Tag 内省搜索器使用)。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_getGeoTagsDetail(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let json = mirage_core::geo::get_geo_tags_detail_json();
    env.new_string(json).unwrap_or_else(|_| JString::from(JObject::null())).into_raw()
}

/// `boolean setUdpMux(boolean enabled)` — 设置是否开启 UDP 多路复用 (UDP Mux)。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_setUdpMux(
    _env: JNIEnv,
    _class: JClass,
    enabled: jboolean,
) -> jboolean {
    mirage_core::proxy::udp_mux::set_udp_mux(enabled != 0, 4);
    1
}

/// `boolean isUdpMux()` — 查询当前是否开启 UDP 多路复用。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_isUdpMux(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if mirage_core::proxy::udp_mux::udp_mux_enabled() {
        1
    } else {
        0
    }
}

/// `String getLogs()` — 导出并排空待处理的内核日志 (流式消费，防止 Binder 事务溢出)。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_getLogs(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let joined = mirage_core::monitor::drain_recent_logs().join("\n");
    env.new_string(joined)
        .unwrap_or_else(|_| JString::from(JObject::null()))
        .into_raw()
}

/// `boolean closeConnection(long id)` — 定向切断指定 ID 的活跃连接。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_closeConnection(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) -> jboolean {
    if id > 0 && mirage_core::monitor::close_connection(id as u64) {
        1
    } else {
        0
    }
}

/// `int closeAllConnections()` — 批量切断并重置所有活跃连接。
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_closeAllConnections(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    mirage_core::monitor::close_all_connections() as jint
}

/// `boolean setOutboundMode(int mode)` — 设置出站分流模式 (0: 规则, 1: 全局代理, 2: 直连)
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_setOutboundMode(
    _env: JNIEnv,
    _class: JClass,
    mode: jint,
) -> jboolean {
    mirage_core::direct::set_outbound_mode(mode as u8);
    1
}

/// `int getOutboundMode()` — 获取当前出站分流模式 (0: 规则, 1: 全局代理, 2: 直连)
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_getOutboundMode(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    mirage_core::direct::get_outbound_mode() as jint
}

/// `String getRecentRequestsJson()` — 获取最近请求流列表 (Surge 级 Recent Requests)
#[no_mangle]
pub extern "system" fn Java_com_mirage_android_core_MirageNative_getRecentRequestsJson(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let json = mirage_core::monitor::get_recent_requests_json();
    env.new_string(json)
        .unwrap_or_else(|_| JString::from(JObject::null()))
        .into_raw()
}



