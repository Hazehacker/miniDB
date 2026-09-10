package top.zhongnan.minidb.storage.pageIndex;

public class PageInfo {
    public int pgno; // 页号
    public int freeSpace; // 空闲空间

    public PageInfo(int pgno, int freeSpace) {
        this.pgno = pgno;
        this.freeSpace = freeSpace;
    }
}
