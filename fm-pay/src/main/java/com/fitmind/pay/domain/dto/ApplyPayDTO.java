package com.fitmind.pay.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 申请支付请求（由 fm-trade 通过 Feign 发起）。
 */
@Data
@Schema(description = "申请支付请求")
public class ApplyPayDTO {

    @Schema(description = "业务订单号（trade_order.id）", example = "1757000000000000000")
    @NotBlank(message = "业务订单号不能为空")
    private String bizOrderNo;

    @Schema(description = "下单用户id", example = "1")
    @NotNull(message = "用户id不能为空")
    private Long bizUserId;

    @Schema(description = "支付金额（分）", example = "49800")
    @NotNull(message = "支付金额不能为空")
    private Integer amount;

    @Schema(description = "支付渠道码", example = "mockPay")
    @NotBlank(message = "支付渠道不能为空")
    private String payChannelCode;
}
