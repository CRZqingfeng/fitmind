package com.fitmind.pay.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 支付结果回调参数。
 *
 * <p>真实渠道的回调参数各有各的格式（微信是 XML、支付宝是 form 表单），
 * 这里统一成 JSON —— 反正<b>流程一样</b>：验签 → 幂等 → 发 MQ。</p>
 */
@Data
@Schema(description = "支付回调参数")
public class PayNotifyDTO {

    @Schema(description = "支付单号")
    private String payOrderNo;

    @Schema(description = "渠道侧的流水号（对账用）")
    private String channelTradeNo;

    @Schema(description = "支付结果：SUCCESS-成功 FAIL-失败")
    private String result;

    @Schema(description = "签名")
    private String sign;
}
