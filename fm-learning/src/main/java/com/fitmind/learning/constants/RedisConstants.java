package com.fitmind.learning.constants;

/**
 * Redis key 与位图相关常量。
 *
 * <p>命名沿用常见实现的约定，方便对照源码。</p>
 */
public interface RedisConstants {

    /** 签到位图 key 前缀，实际 key = sign:uid:{userId}:{yyyyMM} */
    String SIGN_RECORD_KEY_PREFIX = "sign:uid:";

    /** 签到位图后缀格式（含分隔符），如 :202609 */
    String SIGN_DATE_SUFFIX_PATTERN = ":yyyyMM";

    /** 积分实时榜 ZSet key 前缀，实际 key = boards:{yyyyMM} */
    String POINTS_BOARD_KEY_PREFIX = "boards:";

    /** 积分榜 ZSet 后缀格式，如 202609 */
    String POINTS_BOARD_SUFFIX_PATTERN = "yyyyMM";

    /** 签到位图的 TTL（天）。必须 > 2 个月，否则跨月连续签到读不到上月位图 */
    long SIGN_RECORD_TTL_DAYS = 70;
}
