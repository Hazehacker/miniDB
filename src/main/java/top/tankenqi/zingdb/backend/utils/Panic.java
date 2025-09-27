package top.tankenqi.zingdb.backend.utils;

/**
 * 输出异常，并退出程序
 */
public class Panic {
    public static void panic(Exception err) {
        err.printStackTrace();
        System.exit(1);
    }
}
