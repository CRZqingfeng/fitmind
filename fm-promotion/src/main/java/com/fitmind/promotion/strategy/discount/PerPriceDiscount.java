package com.fitmind.promotion.strategy.discount;

import com.fitmind.promotion.domain.po.Coupon;

import static com.fitmind.promotion.strategy.discount.NoThresholdDiscount.fen2yuan;

/**
 * 每满减券：每满 X 减 Y（可以减多次）。
 *
 * <p>例：每满 100 减 10，订单 350 元 → 减 30 元（满 3 个 100）。</p>
 */
public class PerPriceDiscount implements Discount {

    @Override
    public boolean canUse(int totalAmount, Coupon coupon) {
        return totalAmount >= coupon.getThresholdAmount();
    }

    @Override
    public int calculateDiscount(int totalAmount, Coupon coupon) {
        if (!canUse(totalAmount, coupon)) {
            return 0;
        }
        // 能减几次
        int times = totalAmount / coupon.getThresholdAmount();
        int discount = times * coupon.getDiscountValue();
        int max = coupon.getMaxDiscountAmount() == null ? 0 : coupon.getMaxDiscountAmount();
        if (max > 0) {
            discount = Math.min(discount, max);
        }
        return Math.min(discount, totalAmount);
    }

    @Override
    public String getRule(Coupon coupon) {
        String max = (coupon.getMaxDiscountAmount() != null && coupon.getMaxDiscountAmount() > 0)
                ? String.format("，最高减 %s 元", fen2yuan(coupon.getMaxDiscountAmount())) : "";
        return String.format("每满 %s 元减 %s 元%s",
                fen2yuan(coupon.getThresholdAmount()), fen2yuan(coupon.getDiscountValue()), max);
    }
}
