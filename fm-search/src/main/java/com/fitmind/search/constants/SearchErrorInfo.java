package com.fitmind.search.constants;

/**
 * 搜索服务错误提示。
 *
 * <p>与 fm-user / fm-course 的写法保持一致：{@code Msg} 里放对外文案，
 * 抛异常时统一用 {@code BizIllegalException}。</p>
 */
public class SearchErrorInfo {

    public static class Msg {

        public static final String INDEX_WRITE_FAILED = "索引同步失败，请稍后重试";

        public static final String INDEX_QUERY_FAILED = "检索服务暂时不可用，请稍后重试";

        public static final String INTERESTS_EMPTY = "请至少选择一个感兴趣的分类";

        public static final String INTERESTS_TOO_MANY = "最多只能选择 10 个分类";

        public static final String COURSE_DATA_MISSING = "课程数据获取失败，无法同步索引";
    }
}
