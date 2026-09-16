package com.fitmind.pay.constants;

/**
 * 支付模块常量。
 */
public interface PayConstants {

    // ==================== 渠道码 ====================

    /**
     * 模拟支付渠道码。
     *
     * <p>★ 它同时是 {@code MockPayService} 的 <b>Spring Bean 名</b>
     * （{@code @Service(PayConstants.MOCK_CHANNEL_CODE)}）。
     * 业务侧注入 {@code Map<String, IPayService>} 后按渠道码取实现 ——
     * 新增渠道只需要"写一个实现类 + 插一行渠道表"。</p>
     */
    String MOCK_CHANNEL_CODE = "mockPay";

    // ==================== 状态 ====================

    /** 支付单：待支付 */
    int PAY_STATUS_NO_PAY = 1;
    /** 支付单：已支付 */
    int PAY_STATUS_PAYED = 2;
    /** 支付单：已关闭（支付失败 / 取消） */
    int PAY_STATUS_CLOSED = 3;

    // ==================== 单号前缀 ====================

    /** 支付单号前缀 */
    String PAY_ORDER_NO_PREFIX = "PAY";

    /** 退款单号前缀（M4-4）。与支付单号用不同前缀，日志/对账时一眼能分辨 */
    String REFUND_ORDER_NO_PREFIX = "RFD";

    /** 模拟收银台页面路径（Mock 渠道生成的二维码指向它） */
    String MOCK_CASHIER_URL = "http://localhost:10010/ps/mock/cashier/";
}
