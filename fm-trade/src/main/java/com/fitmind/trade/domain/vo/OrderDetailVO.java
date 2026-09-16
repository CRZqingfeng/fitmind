package com.fitmind.trade.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.experimental.Accessors;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单里的课程明细（含下单时的快照）。
 */
@Data
@Accessors(chain = true)
@Schema(description = "订单明细")
public class OrderDetailVO {

    @Schema(description = "★ 订单明细id（退款的内部标识；用户端退款用 courseId，管理端用这个）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "课程id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long courseId;

    @Schema(description = "★ 下单时的课程名（快照，不是现在的名字）")
    private String courseName;

    @Schema(description = "★ 下单时的封面（快照）")
    private String coverUrl;

    @Schema(description = "★ 下单时的单价（分，快照）")
    private Integer price;

    @Schema(description = "分摊到的优惠（分）")
    private Integer discountAmount;

    @Schema(description = "实付（分）")
    private Integer realPayAmount;

    @Schema(description = "★ 下单时课程的有效期（天，快照）")
    private Integer validDuration;

    @Schema(description = "支付成功后算出的课程到期时间")
    private LocalDateTime courseExpireTime;

    @Schema(description = "明细状态：1-待支付 2-已支付")
    private Integer status;
}
