package com.fitmind.data.constants;

/**
 * 数据中心错误提示。
 *
 * <p><b>统计口径的错误必须大声报错，不能静默返回 0</b> ——
 * 一个错误的 0 会被当成"今天真的没人买"，比报错危险得多。</p>
 */
public class DataErrorInfo {
    private DataErrorInfo() {
    }

    public static class Msg {
        private Msg() {
        }

        // ==================== 查询 ====================
        public static final String TREND_DAYS_ILLEGAL = "趋势天数必须在 1~90 之间";
        public static final String DATE_ILLEGAL = "日期格式不正确，应为 yyyyMMdd";
        // ==================== 落库 ====================
        public static final String FLUSH_DATE_REQUIRED = "落库日期不能为空";
    }
}
