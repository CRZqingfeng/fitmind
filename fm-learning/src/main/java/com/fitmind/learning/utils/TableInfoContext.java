package com.fitmind.learning.utils;

/**
 * 动态表名上下文（ThreadLocal）。
 *
 * <p>用法：查历史赛季榜单前 {@code setInfo("points_board_2")}，
 * MyBatis-Plus 的 {@code DynamicTableNameInnerInterceptor} 会把 SQL 里的
 * 逻辑表名 {@code points_board} 替换掉。</p>
 *
 *
 * <p>⚠️ <b>用完必须在 finally 里 remove()</b>：Tomcat 线程池会复用线程，</p>
 * <p>**那 MyBatis 怎么知道这次要操作哪张表？**</p>
 * <p>这就是 `TableInfoContext`（ThreadLocal）存在的意义：
 * 它是一个**线程随身携带的小纸条**，任务开始时写上 " 这次叫 `points_board_5`"，
 * SQL 执行前拦截器看到纸条就把表名替换掉，用完在 `finally` 里擦掉。</p>
 */
public class TableInfoContext {

    private static final ThreadLocal<String> TL = new ThreadLocal<>();

    public static void setInfo(String info) {
        TL.set(info);
    }

    public static String getInfo() {
        return TL.get();
    }

    public static void remove() {
        TL.remove();
    }
}
