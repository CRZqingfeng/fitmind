package com.fitmind.pay.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 申请退款请求（由 fm-trade 通过内部接口发起）。
 *
 * <p><b>★ 退款金额由交易侧给出，但支付侧仍要校验</b>：交易侧是从
 * {@code trade_order_detail.real_pay_amount} 取的，是可信来源；
 * 支付侧再用 {@code pay_order.amount} 复核一遍"退款额 ≤ 支付额"。
 * 两道校验不是为了防彼此，而是为了<b>防代码写错</b> —— 将来谁改了一处逻辑，
 * 另一处会把它挡下来，而不是静默退出去一笔超额的钱。</p>
 */
@Data
@Schema(description = "申请退款请求")
public class RefundApplyDTO {

    @Schema(description = "业务订单号（trade_order.id）")
    @NotBlank(message = "业务订单号不能为空")
    private String bizOrderNo;

    @Schema(description = "★ 业务退款单号（trade.refund_apply.id）—— 支付侧幂等键")
    @NotBlank(message = "业务退款单号不能为空")
    private String bizRefundOrderNo;

    @Schema(description = "退款用户id")
    @NotNull(message = "用户id不能为空")
    private Long bizUserId;

    @Schema(description = "退款金额（分）")
    @NotNull(message = "退款金额不能为空")
    private Integer refundAmount;
}
