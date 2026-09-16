package com.fitmind.api.dto.pay;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 申请退款的请求（fm-trade → fm-pay）。
 *
 * <p>⚠️ 这是<b>契约类</b>，与 fm-pay 内部的同名 DTO 字段保持一致，
 * 但两者是各自模块里的独立类 —— 跨服务只传数据，不共享实现。</p>
 *
 * <p>注意请求里<b>没有"渠道"字段</b>：退款走哪个渠道不由交易侧决定，
 * 而是由支付侧根据原支付单的渠道自动选（用户当初用哪个渠道付的，
 * 就从哪个渠道退）。让上游指定渠道是给 bug 留后门。</p>
 */
@Data
@Accessors(chain = true)
@Schema(description = "申请退款请求")
public class RefundApplyDTO {

    @Schema(description = "业务订单号（trade_order.id）")
    private String bizOrderNo;

    @Schema(description = "★ 业务退款单号（refund_apply.id）—— 支付侧的幂等键")
    private String bizRefundOrderNo;

    @Schema(description = "退款用户id")
    private Long bizUserId;

    @Schema(description = "退款金额（分）—— 由交易侧从订单明细取，绝不能让前端传")
    private Integer refundAmount;
}
