package com.fitmind.pay.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 退款申请的结果（fm-pay → fm-trade）。
 *
 * <p>与 {@code PayResultVO} 平行的一套结构。注意 {@code status} 是
 * <b>"当前这一刻"</b>的状态，不是最终结论 —— 渠道异步退款时这里往往是
 * "退款中"，最终结果要靠 {@code /ps/notify/refund} 回调或补偿任务
 * 通过 MQ 回传。交易侧不应该把这里的返回值当作终态。</p>
 */
@Data
@Accessors(chain = true)
@Schema(description = "退款申请结果")
public class RefundResultVO {

    @Schema(description = "渠道侧退款单号")
    private String refundOrderNo;

    @Schema(description = "★ 业务退款单号（原样回传，交易侧靠它对账）")
    private String bizRefundOrderNo;

    @Schema(description = "业务订单号")
    private String bizOrderNo;

    @Schema(description = "退款金额（分）")
    private Integer refundAmount;

    @Schema(description = "退款状态：1-退款中 2-退款成功 3-退款失败")
    private Integer status;

    @Schema(description = "渠道标识")
    private String refundChannel;

    @Schema(description = "失败原因 / 渠道描述")
    private String message;

    @Schema(description = "是否复用了已存在的退款单（重复申请时）")
    private Boolean reused;
}
