package com.fitmind.trade.constants;

/**
 * 交易模块错误提示。
 */
public class TradeErrorInfo {

    private TradeErrorInfo() {
    }

    /** 错误提示 */
    public static class Msg {

        private Msg() {
        }

        // ==================== 购物车 ====================
        public static final String CART_COURSE_NOT_EXISTS = "课程不存在或已下架";
        public static final String CART_COURSE_DUPLICATE = "《%s》已经在购物车里了";
        public static final String CART_FULL = "购物车最多放 %s 门课程，请先下单或移除";
        public static final String CART_EMPTY = "请先选择要购买的课程";
        public static final String CART_COURSE_ALREADY_BOUGHT = "《%s》你已经买过了，无需重复加入";

        // ==================== 下单 ====================
        public static final String ORDER_COURSE_NOT_ON_SHELF = "《%s》已下架，无法购买";
        public static final String ORDER_COURSE_FREE = "《%s》是免费课程，直接报名即可，无需下单";
        public static final String ORDER_COURSE_ALREADY_BOUGHT = "《%s》你已经买过了，不能重复下单";
        public static final String ORDER_COURSE_HAS_PENDING = "《%s》有一笔待支付的订单，请先完成或取消";
        public static final String ORDER_AMOUNT_ILLEGAL = "订单金额异常，请刷新后重试";
        public static final String ORDER_COURSE_SERVICE_UNAVAILABLE = "课程服务暂时不可用，请稍后重试";
        public static final String ORDER_ID_EXISTS = "订单号已存在，请刷新页面后重试";

        // ==================== 订单 ====================
        public static final String ORDER_NOT_EXISTS = "订单不存在";
        public static final String ORDER_NOT_MINE = "只能操作自己的订单";
        public static final String ORDER_STATUS_TRANSFER_ILLEGAL = "订单状态不允许从【%s】变更为【%s】";
        public static final String ORDER_CANNOT_CANCEL = "只有待支付的订单才能取消";
        public static final String ORDER_ALREADY_CLOSED = "订单已关闭，请重新下单";
        public static final String ORDER_BUSY = "操作太频繁，请稍后再试";
        public static final String ORDER_STATUS_CANNOT_PAY = "只有待支付的订单才能发起支付";
        public static final String PAY_SERVICE_UNAVAILABLE = "支付服务暂时不可用，请稍后重试";
        public static final String PROMOTION_SERVICE_UNAVAILABLE = "促销服务暂时不可用，请稍后重试";
        public static final String COUPON_UNAVAILABLE = "优惠券不可用，请刷新后重试";

        // ==================== 退款（M4-4） ====================
        public static final String REFUND_ORDER_DETAIL_NOT_EXISTS = "订单明细不存在";
        public static final String REFUND_COURSE_NOT_BOUGHT = "你没有购买过这门课程";
        public static final String REFUND_NOT_MINE = "只能对自己的订单申请退款";
        public static final String REFUND_FREE_COURSE = "免费课程无需退款";
        public static final String REFUND_ORDER_STATUS_ILLEGAL = "当前订单状态不支持退款";
        public static final String REFUND_IN_PROGRESS = "该课程有一笔退款正在处理中，请勿重复申请";
        public static final String REFUND_TOO_MANY_TIMES = "同一门课最多申请 %s 次退款";
        public static final String REFUND_APPLY_NOT_EXISTS = "退款申请不存在";
        public static final String REFUND_APPLY_APPROVED = "该申请已审批完成，无法再次操作";
        public static final String REFUND_CANNOT_CANCEL = "只有待审批的退款申请才能撤销";
        public static final String REFUND_NOT_ADMIN = "只有管理员才能审批退款";
        public static final String REFUND_AMOUNT_ILLEGAL = "退款金额异常，请联系客服";
        public static final String REFUND_IN_PROGRESS_ORDER = "该订单有课程正在退款中，暂不能购买";
    }
}
