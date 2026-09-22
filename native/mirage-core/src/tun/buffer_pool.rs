//! 高性能数据面 BufferPool (零堆分配循环复用缓冲池)
//!
//! 在高吞吐或持续网络流场景下，每个入站/出站数据包频繁的 `buf.to_vec()` 和 `vec![0u8; len]`
//! 会导致极高的系统 `malloc` / `free` 频率，引发堆内存碎片、jemalloc/scudo 分配器锁争用
//! 以及 CPU L1/L2 缓存抖动。
//!
//! `BufferPool` 采用 RAII 设计 (`PooledBuf`)，在包生命周期结束时通过 `Drop` 自动回收至 LIFO 栈，
//! 保证缓冲块持续驻留在 CPU 高速缓存中，实现 TUN 数据面零堆分配。

use std::borrow::Borrow;
use std::fmt;
use std::ops::{Deref, DerefMut};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex as StdMutex;

/// 默认单个缓冲块容量 (2048 字节，完全容纳 1500 MTU 及所有 IP/TCP/UDP 头部，且符合 2KB 页面对齐)
pub const DEFAULT_BUF_CAPACITY: usize = 2048;

/// 缓冲池最大驻留包数 (1024 个包 × 2KB ≈ 2MB 常驻上限，绝不无限制膨胀)
pub const MAX_POOL_SIZE: usize = 1024;

/// 允许回收入池的最大容量 (超过 4096 字节的异常巨型包直接释放回操作系统堆，不污染缓冲池)
pub const MAX_RECYCLE_CAPACITY: usize = 4096;

/// 全局静态 TUN 数据面缓冲池
pub static BUFFER_POOL: BufferPool = BufferPool::new(DEFAULT_BUF_CAPACITY, MAX_POOL_SIZE);

/// 线程安全的 LIFO 缓冲池
pub struct BufferPool {
    pool: StdMutex<Vec<Vec<u8>>>,
    default_capacity: usize,
    max_pool_size: usize,
    acquired_count: AtomicU64,
    recycled_count: AtomicU64,
}

impl BufferPool {
    pub const fn new(default_capacity: usize, max_pool_size: usize) -> Self {
        Self {
            pool: StdMutex::new(Vec::new()),
            default_capacity,
            max_pool_size,
            acquired_count: AtomicU64::new(0),
            recycled_count: AtomicU64::new(0),
        }
    }

    /// 从池中获取一块可用缓冲区，容量至少为 `required_cap`
    pub fn acquire(&self, required_cap: usize) -> PooledBuf {
        self.acquired_count.fetch_add(1, Ordering::Relaxed);
        let cap = required_cap.max(self.default_capacity);

        let mut vec = {
            let mut lock = self.pool.lock().unwrap_or_else(|e| e.into_inner());
            lock.pop()
        }
        .unwrap_or_default();

        vec.clear();
        if vec.capacity() < cap {
            vec.reserve(cap);
        }

        PooledBuf { buf: vec }
    }

    /// 回收一块缓冲区至池中
    pub fn recycle(&self, mut vec: Vec<u8>) {
        if vec.capacity() < self.default_capacity || vec.capacity() > MAX_RECYCLE_CAPACITY {
            return;
        }
        vec.clear();
        let mut lock = self.pool.lock().unwrap_or_else(|e| e.into_inner());
        if lock.len() < self.max_pool_size {
            lock.push(vec);
            self.recycled_count.fetch_add(1, Ordering::Relaxed);
        }
    }

    /// 当前池内空闲缓冲数量
    pub fn pool_len(&self) -> usize {
        self.pool.lock().unwrap_or_else(|e| e.into_inner()).len()
    }

    /// 获取历史统计数据 (获取次数, 回收次数, 当前池深度)
    pub fn stats(&self) -> (u64, u64, usize) {
        (
            self.acquired_count.load(Ordering::Relaxed),
            self.recycled_count.load(Ordering::Relaxed),
            self.pool_len(),
        )
    }
}

/// 便捷方法：从全局池获取缓冲区
#[inline]
pub fn acquire_buf(required_cap: usize) -> PooledBuf {
    BUFFER_POOL.acquire(required_cap)
}

/// 便捷方法：从全局池获取默认大小缓冲区
#[inline]
pub fn acquire_default_buf() -> PooledBuf {
    BUFFER_POOL.acquire(DEFAULT_BUF_CAPACITY)
}

/// RAII 自动回收入池的缓冲区包装
pub struct PooledBuf {
    buf: Vec<u8>,
}

impl PooledBuf {
    /// 从切片复制构造一个 PooledBuf (从缓冲池复用内存，避免 heap malloc)
    pub fn from_slice(slice: &[u8]) -> Self {
        let mut pbuf = BUFFER_POOL.acquire(slice.len());
        pbuf.buf.extend_from_slice(slice);
        pbuf
    }

    /// 包装现有的 Vec<u8>，其析构后将自动进入缓冲池供后续重用
    pub fn from_vec(vec: Vec<u8>) -> Self {
        Self { buf: vec }
    }

    /// 从全局池分配指定容量
    #[inline]
    pub fn acquire(cap: usize) -> Self {
        BUFFER_POOL.acquire(cap)
    }

    #[inline]
    pub fn len(&self) -> usize {
        self.buf.len()
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.buf.is_empty()
    }

    #[inline]
    pub fn capacity(&self) -> usize {
        self.buf.capacity()
    }

    #[inline]
    pub fn clear(&mut self) {
        self.buf.clear();
    }

    #[inline]
    pub fn truncate(&mut self, len: usize) {
        self.buf.truncate(len);
    }

    #[inline]
    pub fn resize(&mut self, new_len: usize, value: u8) {
        self.buf.resize(new_len, value);
    }

    #[inline]
    pub fn extend_from_slice(&mut self, slice: &[u8]) {
        self.buf.extend_from_slice(slice);
    }

    #[inline]
    pub fn as_slice(&self) -> &[u8] {
        &self.buf
    }

    #[inline]
    pub fn as_mut_slice(&mut self) -> &mut [u8] {
        &mut self.buf
    }

    #[inline]
    pub fn as_ptr(&self) -> *const u8 {
        self.buf.as_ptr()
    }

    #[inline]
    pub fn as_mut_ptr(&mut self) -> *mut u8 {
        self.buf.as_mut_ptr()
    }

    /// 提取内部 Vec<u8>，剥离对象将不会回收入池
    pub fn into_vec(mut self) -> Vec<u8> {
        std::mem::take(&mut self.buf)
    }
}

impl Drop for PooledBuf {
    fn drop(&mut self) {
        if self.buf.capacity() >= BUFFER_POOL.default_capacity
            && self.buf.capacity() <= MAX_RECYCLE_CAPACITY
        {
            let mut vec = std::mem::take(&mut self.buf);
            vec.clear();
            BUFFER_POOL.recycle(vec);
        }
    }
}

impl Deref for PooledBuf {
    type Target = [u8];

    #[inline]
    fn deref(&self) -> &Self::Target {
        &self.buf
    }
}

impl DerefMut for PooledBuf {
    #[inline]
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.buf
    }
}

impl AsRef<[u8]> for PooledBuf {
    #[inline]
    fn as_ref(&self) -> &[u8] {
        &self.buf
    }
}

impl AsMut<[u8]> for PooledBuf {
    #[inline]
    fn as_mut(&mut self) -> &mut [u8] {
        &mut self.buf
    }
}

impl Borrow<[u8]> for PooledBuf {
    #[inline]
    fn borrow(&self) -> &[u8] {
        &self.buf
    }
}

impl Clone for PooledBuf {
    fn clone(&self) -> Self {
        Self::from_slice(&self.buf)
    }
}

impl Default for PooledBuf {
    fn default() -> Self {
        Self::acquire(DEFAULT_BUF_CAPACITY)
    }
}

impl fmt::Debug for PooledBuf {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("PooledBuf")
            .field("len", &self.buf.len())
            .field("capacity", &self.buf.capacity())
            .finish()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    static TEST_LOCK: StdMutex<()> = StdMutex::new(());

    #[test]
    fn test_acquire_and_recycle() {
        let _lock = TEST_LOCK.lock().unwrap();
        let pbuf = acquire_default_buf();
        assert!(pbuf.capacity() >= DEFAULT_BUF_CAPACITY);
        assert_eq!(pbuf.len(), 0);

        let initial_recycled = BUFFER_POOL.stats().1;
        drop(pbuf);
        let current_recycled = BUFFER_POOL.stats().1;
        assert_eq!(current_recycled, initial_recycled + 1);
        assert!(BUFFER_POOL.pool_len() > 0);
    }

    #[test]
    fn test_from_slice_and_deref() {
        let _lock = TEST_LOCK.lock().unwrap();
        let data = b"hello mirage buffer pool";
        let mut pbuf = PooledBuf::from_slice(data);
        assert_eq!(&*pbuf, data);
        assert_eq!(pbuf.len(), data.len());

        pbuf[0] = b'H';
        assert_eq!(&pbuf[0..5], b"Hello");
    }

    #[test]
    fn test_oversized_buffers_not_recycled() {
        let _lock = TEST_LOCK.lock().unwrap();
        // 大于 MAX_RECYCLE_CAPACITY 的超大缓冲直接还给 OS 堆，不回收入池
        let initial_recycled = BUFFER_POOL.stats().1;
        let pbuf = PooledBuf::acquire(MAX_RECYCLE_CAPACITY + 1024);
        assert!(pbuf.capacity() > MAX_RECYCLE_CAPACITY);
        drop(pbuf);
        let final_recycled = BUFFER_POOL.stats().1;
        assert_eq!(initial_recycled, final_recycled, "超大缓冲不应被回收进入池");
    }

    #[test]
    fn test_concurrent_acquire_recycle() {
        let _lock = TEST_LOCK.lock().unwrap();
        let mut handles = Vec::new();
        for _ in 0..8 {
            let h = std::thread::spawn(|| {
                for i in 0..100 {
                    let mut b = acquire_buf(500);
                    b.extend_from_slice(&[i as u8; 256]);
                    assert_eq!(b.len(), 256);
                }
            });
            handles.push(h);
        }
        for h in handles {
            h.join().unwrap();
        }
        assert!(BUFFER_POOL.pool_len() <= MAX_POOL_SIZE);
    }
}
