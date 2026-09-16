package com.fitmind.promotion.strategy.discount;

import com.fitmind.promotion.domain.po.Coupon;

import static com.fitmind.promotion.strategy.discount.NoThresholdDiscount.fen2yuan;

/**
 * 折扣券：打 N 折（discountValue 存折扣率，8 折存 80）。
 */
public class RateDiscount implements Discount {

    @Override
    public boolean canUse(int totalAmount, Coupon coupon) {
        return totalAmount >= coupon.getThresholdAmount();
    }

    @Override
    public int calculateDiscount(int totalAmount, Coupon coupon) {
        if (!canUse(totalAmount, coupon)) {
            return 0;
        }
        // 优惠 = 总价 × (1 - 折扣率/100)
        int discount = (int) (totalAmount * (100 - coupon.getDiscountValue()) / 100.0);
        int max = coupon.getMaxDiscountAmount() == null ? 0 : coupon.getMaxDiscountAmount();
        if (max > 0) {
            discount = Math.min(discount, max);
        }
        return Math.min(discount, totalAmount);
    }

    @Override
    public String getRule(Coupon coupon) {
        String threshold = coupon.getThresholdAmount() > 0
                ? String.format("满 %s 元，", fen2yuan(coupon.getThresholdAmount())) : "";
        String max = (coupon.getMaxDiscountAmount() != null && coupon.getMaxDiscountAmount() > 0)
                ? String.format("，最高减 %s 元", fen2yuan(coupon.getMaxDiscountAmount())) : "";
        return String.format("%s%.1f 折%s", threshold, coupon.getDiscountValue() / 10.0, max);
    }
}
