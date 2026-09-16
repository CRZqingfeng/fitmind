package com.fitmind.trade.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 发起支付的结果（给前端渲染二维码）。
 */
@Data
@Accessors(chain = true)
@Schema(description = "发起支付结果")
public class PayUrlVO {

    @Schema(description = "订单id")
    private Long orderId;

    @Schema(description = "支付单号")
    private String payOrderNo;

    @Schema(description = "支付链接（二维码内容）")
    private String qrCodeUrl;

    @Schema(description = "应付金额（分）")
    private Integer amount;

    @Schema(description = "支付截止时间")
    private LocalDateTime expireTime;

    @Schema(description = "是否复用了已有的待支付单")
    private Boolean reused;
}
