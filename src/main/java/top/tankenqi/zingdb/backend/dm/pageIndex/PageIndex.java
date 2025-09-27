package top.tankenqi.zingdb.backend.dm.pageIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import top.tankenqi.zingdb.backend.dm.pageCache.PageCache;

public class PageIndex {

    private static final int INTERVALS_NO = 40; // 将一页划成40个区间
    private static final int THRESHOLD = PageCache.PAGE_SIZE / INTERVALS_NO;

    private Lock lock;
    // lists[i] 存储空闲空间在 [i*THRESHOLD, (i+1)*THRESHOLD) 范围内的页
    private List<PageInfo>[] lists;

    @SuppressWarnings("unchecked")
    public PageIndex() {
        lock = new ReentrantLock();
        lists = new List[INTERVALS_NO + 1];
        for (int i = 0; i < INTERVALS_NO + 1; i++) {
            lists[i] = new ArrayList<>();
        }
    }

    public void add(int pgno, int freeSpace) {
        lock.lock();
        try {
            int number = freeSpace / THRESHOLD;
            // 在上层模块使用完这个页面后，需要将其重新插入 PageIndex
            lists[number].add(new PageInfo(pgno, freeSpace));
        } finally {
            lock.unlock();
        }
    }

    public PageInfo select(int spaceSize) {
        lock.lock();

        /**
         * 从能容纳目标大小的最小区间开始查找，找到就移除并返回
         */
        try {
            int number = spaceSize / THRESHOLD;
            if (number < INTERVALS_NO)
                number++;
            while (number <= INTERVALS_NO) {
                if (lists[number].size() == 0) {
                    number++;
                    continue;
                }
                /**
                 * 被选择的页，会直接从 PageIndex 中移除，
                 * 这意味着，同一个页面是不允许并发写的
                 */
                return lists[number].remove(0);
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

}
