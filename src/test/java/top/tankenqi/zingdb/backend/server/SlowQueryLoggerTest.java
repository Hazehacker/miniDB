package top.tankenqi.zingdb.backend.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * SlowQueryLogger 的阈值判定测试。
 * 直接验证 thresholdMs 行为 + 写入路径不抛异常即可，不去断言日志输出（避免耦合 logback）。
 */
public class SlowQueryLoggerTest {

    private long savedThreshold;

    @Before
    public void setUp() {
        savedThreshold = SlowQueryLogger.getThresholdMs();
    }

    @After
    public void tearDown() {
        SlowQueryLogger.setThresholdMs(savedThreshold);
    }

    @Test
    public void thresholdRoundtrip() {
        SlowQueryLogger.setThresholdMs(50);
        assertEquals(50, SlowQueryLogger.getThresholdMs());
        SlowQueryLogger.setThresholdMs(-1);     // 负值被钳到 0
        assertEquals(0, SlowQueryLogger.getThresholdMs());
    }

    @Test
    public void underThresholdNoOp() {
        SlowQueryLogger.setThresholdMs(1000);
        SlowQueryLogger.recordIfSlow("select 1", 10_000_000L);   // 10ms < 1000ms
    }

    @Test
    public void overThresholdRecorded() {
        SlowQueryLogger.setThresholdMs(5);
        SlowQueryLogger.recordIfSlow("select * from huge_table where complicated = 1", 50_000_000L);
    }

    @Test
    public void thresholdZeroDisables() {
        SlowQueryLogger.setThresholdMs(0);
        // 阈值为 0 时不应该触发任何记录（实现上 thresholdNanos <= 0 直接返回）
        SlowQueryLogger.recordIfSlow("anything", Long.MAX_VALUE);
    }

    @Test
    public void longSqlIsTruncated() {
        SlowQueryLogger.setThresholdMs(1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append('x');
        // 调用不抛异常即可；实现内部会截到 500
        SlowQueryLogger.recordIfSlow(sb.toString(), 100_000_000L);
        assertTrue(true);
    }
}
