package top.tankenqi.zingdb.backend.common;

import static org.junit.Assert.assertEquals;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class CacheCloseTest {
    /** 关闭多个仍被引用的资源必须全部释放，不能在遍历中修改 HashMap 后抛异常。 */
    @Test public void closesAllOutstandingResources() throws Exception {
        Set<Long> released = new HashSet<>();
        AbstractCache<Long> cache = new AbstractCache<Long>(0) {
            protected Long getForCache(long key) { return key; }
            protected void releaseForCache(Long value) { released.add(value); }
        };
        cache.get(1); cache.get(2); cache.get(3);
        cache.close();
        assertEquals(3, released.size());
    }
}
