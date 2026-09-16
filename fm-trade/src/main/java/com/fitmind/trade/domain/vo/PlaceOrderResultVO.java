package com.fitmind.trade.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.experimental.Accessors;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 下单结果。
 */
@Data
@Accessors(chain = true)
@Schema(description = "下单结果")
public class PlaceOrderResultVO {

    @Schema(description = "订单id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    @Schema(description = "实付金额（分）")
    private Integer realAmount;

    @Schema(description = "支付截止时间")
    private LocalDateTime payOutTime;

    /** 是不是"这次真的新建了一笔单"（false 表示命中了已存在的订单，属于重复提交） */
    @Schema(description = "是否本次新建；false 表示重复提交，返回的是已有订单")
    private Boolean created;
}
