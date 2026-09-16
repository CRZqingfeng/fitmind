package com.fitmind.api.dto.pay;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 退款结果（fm-pay → fm-trade）。
 *
 * <p><b>★ 注意 {@code status} 不是终态</b>：申请退款时渠道通常只回"已受理"，
 * 真正的结果通过 MQ（{@code refund.result}）异步回到交易侧。
 * 交易侧把它当"当前快照"用，不要用它做最终判断。</p>
 */
@Data
@Schema(description = "退款结果")
public class RefundResultDTO {

    @Schema(description = "渠道侧退款单号")
    private String refundOrderNo;

    @Schema(description = "业务退款单号（原样回传）")
    private String bizRefundOrderNo;

    @Schema(description = "业务订单号")
    private String bizOrderNo;

    @Schema(description = "退款金额（分）")
    private Integer refundAmount;

    @Schema(description = "退款状态：1-退款中 2-退款成功 3-退款失败")
    private Integer status;

    @Schema(description = "渠道标识")
    private String refundChannel;

    @Schema(description = "渠道描述 / 失败原因")
    private String message;

    @Schema(description = "是否复用了已存在的退款单（重复申请时）")
    private Boolean reused;
}
