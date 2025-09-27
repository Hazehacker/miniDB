package top.tankenqi.zingdb.backend.common;

/**
 * SubArray 是一个用于表示一个字节数组中的一部分的类
 * 因为java做不到数组的零拷贝，所以需要使用这个类来表示一个字节数组中的一部分
 * 下面是一个例子：
 * // 一个 8KB 的页包含多个数据项
 * byte[] pageData = new byte[8192]; // 页数据
 * 
 * // 数据项1：偏移 100-200
 * // 数据项2：偏移 200-350
 * // 数据项3：偏移 350-500
 * 
 * // 传统方式（会复制）：
 * byte[] dataItem1 = Arrays.copyOfRange(pageData, 100, 200); // 复制100字节
 * byte[] dataItem2 = Arrays.copyOfRange(pageData, 200, 350); // 复制150字节
 * 
 * // SubArray 方式（零拷贝）：
 * SubArray dataItem1 = new SubArray(pageData, 100, 200); // 只是记录范围
 * SubArray dataItem2 = new SubArray(pageData, 200, 350); // 只是记录范围
 */
public class SubArray {
    public byte[] raw;
    public int start;
    public int end;

    public SubArray(byte[] raw, int start, int end) {
        this.raw = raw;
        this.start = start;
        this.end = end;
    }
}
