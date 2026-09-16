package com.fitmind.api.dto.pay;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 申请支付的结果（fm-pay → fm-trade）。
 */
@Data
@Schema(description = "支付申请结果")
public class PayResultDTO {

    @Schema(description = "支付单号")
    private String payOrderNo;

    @Schema(description = "支付链接（二维码内容）")
    private String qrCodeUrl;

    @Schema(description = "支付截止时间")
    private LocalDateTime expireTime;

    @Schema(description = "是否复用了已有的待支付单")
    private Boolean reused;
}
