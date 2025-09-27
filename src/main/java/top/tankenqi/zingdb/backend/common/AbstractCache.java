package top.tankenqi.zingdb.backend.common;

import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import top.tankenqi.zingdb.common.Error;

/**
 * AbstractCache 实现了一个引用计数策略的缓存
 */
public abstract class AbstractCache<T> {
    /**
     * 抽象层 AbstractCache 设计成用 long 作为通用键类型，
     * 方便被不同子类复用，子类中的key实际是int或者是long都没关系
     */
    private HashMap<Long, T> cache;
    private HashMap<Long, Integer> references; // 元素的引用个数
    private HashMap<Long, Boolean> getting; // 正在获取某资源的线程

    private int maxResource; // 资源的最大数量，即可容纳资源T的最大数量
    private int count = 0;
    private Lock lock;

    public AbstractCache(int maxResource) {
        this.maxResource = maxResource;
        cache = new HashMap<>();
        references = new HashMap<>();
        getting = new HashMap<>();
        lock = new ReentrantLock();
    }

    protected T get(long key) throws Exception {
        while (true) {
            lock.lock();

            /**
             * 首先就需要检查这个时候是否有其他线程正在从数据源获取这个资源，
             * 如果有，就过会再来看看
             */
            if (getting.containsKey(key)) {
                // 请求的资源正在被其他线程获取
                lock.unlock();
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    continue;
                }
                continue;
            }

            if (cache.containsKey(key)) {
                // 资源在缓存中，直接返回
                T obj = cache.get(key);
                references.put(key, references.get(key) + 1);
                lock.unlock();
                return obj;
            }

            /**
             * 资源不在缓存中，尝试获取该资源,
             * 如果缓存已满，则抛出异常
             */
            if (maxResource > 0 && count == maxResource) {
                lock.unlock();
                throw Error.CacheFullException;
            }
            count++; // 在缓存未命中、准备回源加载前“预占一个名额”
            getting.put(key, true);
            lock.unlock();
            break;
        }

        T obj = null;
        try {
            // 在锁外进行可能非常耗时的回源操作
            obj = getForCache(key);
        } catch (Exception e) {
            // 回源失败，需要将count和getting状态还原
            lock.lock();
            count--;
            getting.remove(key);
            lock.unlock();
            throw e;
        }

        // 回源成功，将资源放入缓存
        lock.lock();
        getting.remove(key);
        cache.put(key, obj);
        references.put(key, 1);
        lock.unlock();

        return obj;
    }

    /**
     * 强行释放一个缓存
     */
    protected void release(long key) {
        lock.lock();
        try {
            int ref = references.get(key) - 1;
            if (ref == 0) {
                T obj = cache.get(key);
                releaseForCache(obj);
                references.remove(key);
                cache.remove(key);
                count--;
            } else {
                references.put(key, ref);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 关闭缓存，写回所有资源
     */
    protected void close() {
        lock.lock();
        try {
            Set<Long> keys = cache.keySet();
            for (long key : keys) {
                T obj = cache.get(key);
                releaseForCache(obj);
                references.remove(key);
                cache.remove(key);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 当资源不在缓存时的获取行为
     */
    protected abstract T getForCache(long key) throws Exception;

    /**
     * 当资源被驱逐时的写回行为
     */
    protected abstract void releaseForCache(T obj);
}
