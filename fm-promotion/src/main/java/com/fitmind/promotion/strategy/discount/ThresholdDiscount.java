package com.fitmind.promotion.strategy.discount;

import com.fitmind.promotion.domain.po.Coupon;

import static com.fitmind.promotion.strategy.discount.NoThresholdDiscount.fen2yuan;

/**
 * 满减券：满 X 减 Y（只减一次）。
 */
public class ThresholdDiscount implements Discount {

    @Override
    public boolean canUse(int totalAmount, Coupon coupon) {
        return totalAmount >= coupon.getThresholdAmount();
    }

    @Override
    public int calculateDiscount(int totalAmount, Coupon coupon) {
        if (!canUse(totalAmount, coupon)) {
            return 0;
        }
        int discount = coupon.getDiscountValue();
        // 最高优惠限制（0 表示不限）
        int max = coupon.getMaxDiscountAmount() == null ? 0 : coupon.getMaxDiscountAmount();
        if (max > 0) {
            discount = Math.min(discount, max);
        }
        return Math.min(discount, totalAmount);
    }

    @Override
    public String getRule(Coupon coupon) {
        return String.format("满 %s 元减 %s 元",
                fen2yuan(coupon.getThresholdAmount()), fen2yuan(coupon.getDiscountValue()));
    }
}
