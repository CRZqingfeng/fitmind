package com.fitmind.promotion.constants;

/**
 * 促销模块错误提示。
 */
public class PromotionErrorInfo {

    private PromotionErrorInfo() {
    }

    public static class Msg {

        private Msg() {
        }

        public static final String COUPON_NOT_EXISTS = "优惠券不存在";
        public static final String COUPON_NOT_ISSUING = "优惠券不在发放中（可能未开始或已结束）";
        public static final String COUPON_NOT_BEGIN = "优惠券还没到领取时间";
        public static final String COUPON_EXPIRED = "优惠券已过期";
        public static final String COUPON_SOLD_OUT = "优惠券已被领完";
        public static final String COUPON_USER_LIMIT = "每人限领 %s 张，你已经领过了";
        public static final String COUPON_RECEIVE_BUSY = "领券太火爆了，请稍后再试";
        public static final String COUPON_OBTAIN_WAY_ILLEGAL = "该优惠券只能通过兑换码领取";
        public static final String CODE_INVALID = "无效兑换码";
        public static final String CODE_EXCHANGED = "兑换码已被使用";
        public static final String CODE_NOT_EXCHANGE_WAY = "该优惠券不支持兑换码领取";
        public static final String USER_COUPON_NOT_EXISTS = "你没有这张优惠券";
        public static final String USER_COUPON_INVALID = "优惠券已失效或已使用";
        public static final String USER_COUPON_EXPIRED = "优惠券已过期";
        public static final String DISCOUNT_TYPE_ILLEGAL = "不支持的折扣类型";
    }
}
