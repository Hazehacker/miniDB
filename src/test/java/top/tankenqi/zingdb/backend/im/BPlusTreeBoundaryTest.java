package top.tankenqi.zingdb.backend.im;

import static org.junit.Assert.*;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;
import top.tankenqi.zingdb.backend.dm.MockDataManager;

/** 路由测试仅用内存 DM 替代磁盘/WAL，执行真实节点分裂、查找和叶子链遍历。 */
public class BPlusTreeBoundaryTest {
    @Test public void duplicatesRemainCompleteAcrossInternalSplits() throws Exception {
        MockDataManager dm = MockDataManager.newMockDataManager();
        BPlusTree tree = BPlusTree.load(BPlusTree.create(dm), dm);
        try {
            for (int i = 0; i < 2300; i++) tree.insert(-1, i + 1);
            List<Long> found = tree.search(-1);
            assertEquals(2300, found.size());
            assertEquals(2300, new HashSet<>(found).size());
            assertTrue(found.contains(1L));
            assertTrue(found.contains(2300L));
        } finally { tree.close(); }
    }
    @Test public void longExtremesRemainReachableAcrossInternalSplits() throws Exception {
        MockDataManager dm = MockDataManager.newMockDataManager();
        BPlusTree tree = BPlusTree.load(BPlusTree.create(dm), dm);
        try {
            for (int i = 0; i < 2300; i++) tree.insert(i, i + 1);
            tree.insert(Long.MIN_VALUE, 10001);
            tree.insert(Long.MAX_VALUE, 10002);
            assertEquals(java.util.Arrays.asList(10001L), tree.search(Long.MIN_VALUE));
            assertEquals(java.util.Arrays.asList(10002L), tree.search(Long.MAX_VALUE));
            assertEquals(2302, tree.searchRange(Long.MIN_VALUE, Long.MAX_VALUE).size());
        } finally { tree.close(); }
    }
}
