package com.fitmind.promotion.strategy.discount;

import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.promotion.constants.PromotionConstants;
import com.fitmind.promotion.constants.PromotionErrorInfo;

import java.util.EnumMap;

/**
 * 折扣策略的注册表（按折扣类型路由）。
 */
public enum DiscountStrategy {

    THRESHOLD(PromotionConstants.DISCOUNT_THRESHOLD, new ThresholdDiscount()),
    PER(PromotionConstants.DISCOUNT_PER, new PerPriceDiscount()),
    RATE(PromotionConstants.DISCOUNT_RATE, new RateDiscount()),
    NO_THRESHOLD(PromotionConstants.DISCOUNT_NO_THRESHOLD, new NoThresholdDiscount()),
    ;

    private final int type;
    private final Discount discount;

    DiscountStrategy(int type, Discount discount) {
        this.type = type;
        this.discount = discount;
    }

    public static Discount getDiscount(Integer type) {
        if (type == null) {
            throw new BizIllegalException(PromotionErrorInfo.Msg.DISCOUNT_TYPE_ILLEGAL);
        }
        for (DiscountStrategy s : values()) {
            if (s.type == type) {
                return s.discount;
            }
        }
        throw new BizIllegalException(PromotionErrorInfo.Msg.DISCOUNT_TYPE_ILLEGAL);
    }
}
