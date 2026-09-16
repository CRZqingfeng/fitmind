package com.fitmind.promotion.constants;

/**
 * 促销模块常量（Redis key 与业务约定值）。
 */
public interface PromotionConstants {

    // ==================== Redis key ====================

    /**
     * 券的缓存：Hash，field = totalNum / userLimit / issueEndTime。
     * <p>秒杀领券的 Lua 脚本直接读它 —— 库存判断<b>不查数据库</b>。</p>
     */
    String COUPON_CACHE_KEY_PREFIX = "prs:coupon:";

    /**
     * 某张券的"每人已领数量"：Hash，field = userId，value = 已领数。
     */
    String COUPON_USER_LIMIT_KEY_PREFIX = "prs:user:coupon:";

    /**
     * ★ 兑换码位图：bitmap，offset = 兑换码序列号，1 表示已兑换。
     *
     * <p>这是"20 亿兑换码"能落地的关键之一 —— 一个码只占 1 bit，
     * 20 亿个码约 256MB，而且是常数级的（不随券的数量线性增长）。</p>
     */
    String COUPON_CODE_MAP_KEY = "prs:codes";

    /**
     * ★ 兑换码序号 → 券id 的映射：ZSet，member = 券id，score = 该券分到的最大序号。
     *
     * <p>查"序号 s 属于哪张券"：{@code ZRANGEBYSCORE key s +inf LIMIT 0 1}
     * 取第一个 score >= s 的券。因为每个券占据一段连续的序号区间，
     * 所以不需要为每个码存一条映射。</p>
     */
    String COUPON_RANGE_KEY = "prs:code:range";

    /** 兑换码序列号的发号器（Redis 自增） */
    String COUPON_CODE_SERIAL_KEY = "prs:code:serial";

    // ==================== 折扣类型 ====================
    int DISCOUNT_THRESHOLD = 1;   // 满减
    int DISCOUNT_PER = 2;         // 每满减
    int DISCOUNT_RATE = 3;        // 折扣
    int DISCOUNT_NO_THRESHOLD = 4;// 无门槛

    // ==================== 券状态（coupon.status） ====================
    int COUPON_DRAFT = 1;
    int COUPON_ISSUING = 2;
    int COUPON_FINISHED = 3;

    // ==================== 用户券状态 ====================
    int USER_COUPON_UNUSED = 1;
    int USER_COUPON_USED = 2;
    int USER_COUPON_INVALID = 3;

    // ==================== 获取方式 ====================
    int OBTAIN_MANUAL = 1;
    int OBTAIN_CODE = 2;

    // ==================== 限定范围类型 ====================
    int SCOPE_CATEGORY = 1;
    int SCOPE_COURSE = 2;

    // ==================== 兑换码状态 ====================
    int CODE_UNUSED = 1;
    int CODE_USED = 2;
}
