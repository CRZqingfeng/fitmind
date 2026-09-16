package com.fitmind.pay.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 渠道退款回调参数。
 *
 * <p>⚠️ <b>参与签名的字段必须与渠道侧严格对齐</b>
 * （这里是 {@code refundOrderNo} + {@code result}）——
 * M4-2 支付回调上踩过一次：两边字段对不齐导致签名永远验不过，
 * 而且报错信息只说"验签失败"，看不出是字段不一致。
 * 所以 Mock 渠道侧（{@code MockRefundController}）的签名参数
 * 与这里保持<b>逐字段相同</b>，并在两侧互相注明。</p>
 */
@Data
@Schema(description = "渠道退款回调参数")
public class RefundNotifyDTO {

    @Schema(description = "渠道侧退款单号")
    private String refundOrderNo;

    @Schema(description = "退款结果：SUCCESS / FAIL")
    private String result;

    @Schema(description = "渠道流水号（对账用，不参与签名）")
    private String channelRefundNo;

    @Schema(description = "签名")
    private String sign;
}
