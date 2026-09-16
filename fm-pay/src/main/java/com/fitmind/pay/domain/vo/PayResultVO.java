package com.fitmind.pay.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 申请支付的结果（给交易模块 / 前端）。
 */
@Data
@Accessors(chain = true)
@Schema(description = "支付申请结果")
public class PayResultVO {

    @Schema(description = "支付单号")
    private String payOrderNo;

    @Schema(description = "支付链接（二维码内容；Mock 渠道是本地模拟收银台）")
    private String qrCodeUrl;

    @Schema(description = "支付截止时间")
    private LocalDateTime expireTime;

    @Schema(description = "是否复用了已有的待支付单（同一业务订单重复发起支付时）")
    private Boolean reused;
}
