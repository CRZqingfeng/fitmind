package com.fitmind.promotion.strategy.discount;

import com.fitmind.promotion.domain.po.Coupon;

/**
 * 折扣计算策略。
 *
 * <p>四种券（满减 / 每满减 / 折扣 / 无门槛）的算法各不相同，
 * 用 if-else 堆在一个方法里会越来越难维护；每种券一个类，
 * 加新券型时只需新增一个实现类 + 在 {@link DiscountStrategy} 里注册。</p>
 */
public interface Discount {

    /** 当前金额是否满足这张券的使用门槛 */
    boolean canUse(int totalAmount, Coupon coupon);

    /** 计算优惠金额（分） */
    int calculateDiscount(int totalAmount, Coupon coupon);

    /** 规则描述（给前端直接展示，不用自己拼字符串） */
    String getRule(Coupon coupon);
}
