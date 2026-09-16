package com.fitmind.pay.constants;

/**
 * 支付模块错误提示。
 */
public class PayErrorInfo {

    private PayErrorInfo() {
    }

    /** 错误提示 */
    public static class Msg {

        private Msg() {
        }

        public static final String CHANNEL_NOT_SUPPORTED = "不支持的支付渠道：%s";
        public static final String CHANNEL_DISABLED = "支付渠道【%s】已停用";
        public static final String PAY_ORDER_NOT_EXISTS = "支付单不存在";
        public static final String PAY_ORDER_NOT_MINE = "只能操作自己的支付单";
        public static final String PAY_ORDER_STATUS_ILLEGAL = "支付单状态不允许该操作";
        public static final String PAY_ORDER_EXPIRED = "支付单已过期，请重新下单";
        public static final String PAY_ORDER_AMOUNT_ILLEGAL = "支付金额异常";
        public static final String NOTIFY_SIGN_INVALID = "回调签名校验失败";
        public static final String NOTIFY_PARAM_ILLEGAL = "回调参数不完整";

        // ==================== 退款（M4-4） ====================
        public static final String REFUND_ORDER_NOT_EXISTS = "退款单不存在";
        public static final String REFUND_PAY_ORDER_NOT_PAID = "原支付单未支付成功，无法退款";
        public static final String REFUND_AMOUNT_ILLEGAL = "退款金额异常";
        public static final String REFUND_AMOUNT_EXCEED = "退款金额（%s分）超过实付金额（%s分）";
        public static final String REFUND_TOTAL_EXCEED = "该订单已退 %s 分，本次再退 %s 分将超过实付 %s 分";
        public static final String REFUND_NOTIFY_RESULT_INVALID = "回调结果非法，只能是 SUCCESS 或 FAIL";
    }
}
