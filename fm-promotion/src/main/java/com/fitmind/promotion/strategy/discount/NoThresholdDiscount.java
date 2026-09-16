package com.fitmind.promotion.strategy.discount;

import com.fitmind.promotion.domain.po.Coupon;

/**
 * 无门槛券：直接减固定金额。
 */
public class NoThresholdDiscount implements Discount {

    @Override
    public boolean canUse(int totalAmount, Coupon coupon) {
        return true;
    }

    @Override
    public int calculateDiscount(int totalAmount, Coupon coupon) {
        // 优惠不能超过订单金额本身（否则会出现负数实付）
        return Math.min(coupon.getDiscountValue(), totalAmount);
    }

    @Override
    public String getRule(Coupon coupon) {
        return String.format("无门槛减 %s 元", fen2yuan(coupon.getDiscountValue()));
    }

    static String fen2yuan(int fen) {
        return String.format("%.2f", fen / 100.0);
    }
}
