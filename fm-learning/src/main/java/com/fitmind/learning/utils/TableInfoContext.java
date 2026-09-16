package com.fitmind.learning.utils;

/**
 * 动态表名上下文（ThreadLocal）。
 *
 * <p>用法：查历史赛季榜单前 {@code setInfo("points_board_2")}，
 * MyBatis-Plus 的 {@code DynamicTableNameInnerInterceptor} 会把 SQL 里的
 * 逻辑表名 {@code points_board} 替换掉。</p>
 *
 * <p>⚠️ <b>用完必须在 finally 里 remove()</b>：Tomcat 线程池会复用线程，
 * 不清理会让下一次普通查询（比如查 points_record）也被改成榜单分表，
 * 报"表不存在"或查错表。</p>
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
