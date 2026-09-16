package com.fitmind.api.dto.pay;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 申请支付的请求（fm-trade → fm-pay）。
 *
 * <p>⚠️ 这是<b>契约类</b>，与 fm-pay 内部的同名 DTO 字段保持一致，
 * 但两者是各自模块里的独立类 —— 跨服务只传数据，不共享实现。</p>
 */
@Data
@Schema(description = "申请支付请求")
public class ApplyPayDTO {

    @Schema(description = "业务订单号（trade_order.id）")
    private String bizOrderNo;

    @Schema(description = "下单用户id")
    private Long bizUserId;

    @Schema(description = "支付金额（分）—— 由交易侧从订单里取，绝不能让前端传")
    private Integer amount;

    @Schema(description = "支付渠道码")
    private String payChannelCode;
}
