package com.fitmind.data.constants;

/**
 * 数据中心常量。
 */
public interface DataConstants {

    /**
     * Redis 日计数 key：{@code data:daily:{yyyyMMdd}}。
     *
     * <p>用 <b>hash</b> 而不是多个 String key：一天有 8 个指标，
     * 8 个 key 就要 8 次 IO、8 份过期时间管理；一个 hash 一次 HGETALL 全出来。</p>
     */
    String DAILY_KEY_PREFIX = "data:daily:";

    /**
     * Redis 课程销量榜 key：{@code data:course:rank:{yyyyMMdd}}（ZSet）。
     *
     * <p>用 ZSet 的理由很直接：销量榜要按销量倒序取 TopN，
     * ZSet 的 {@code ZINCRBY} 累加和 {@code ZREVRANGE} 取榜都是 O(logN)，
     * 而 hash 要取 TopN 就得全量拉回来在内存里排序。</p>
     */
    String COURSE_RANK_KEY_PREFIX = "data:course:rank:";

    /**
     * Redis 课程销售额 key：{@code data:course:amount:{yyyyMMdd}}（hash，field=courseId）。
     *
     * <p>为什么销售额不塞进 ZSet 的 score：score 已经被销量占了。
     * 一个 ZSet 只能按一个维度排序，所以金额另存一个 hash。</p>
     */
    String COURSE_AMOUNT_KEY_PREFIX = "data:course:amount:";

    /**
     * Redis 课程退款数 key：{@code data:course:refund:{yyyyMMdd}}（hash，field=courseId）。
     */
    String COURSE_REFUND_KEY_PREFIX = "data:course:refund:";

    /** 日期格式 yyyyMMdd（★ 用它当 int 存库，比 Date 好排序、好建索引） */
    String DAY_PATTERN = "yyyyMMdd";

    /** 默认趋势天数 */
    int DEFAULT_TREND_DAYS = 7;
    /** 趋势最多查多少天（防止前端传 3650 把数据库拖垮） */
    int MAX_TREND_DAYS = 90;
}
