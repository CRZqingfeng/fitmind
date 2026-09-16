package com.fitmind.promotion.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 我的优惠券展示。
 */
@Data
@Accessors(chain = true)
@Schema(description = "我的优惠券")
public class CouponVO {

    @Schema(description = "用户券id（下单时传这个）")
    private Long id;

    @Schema(description = "券模板id")
    private Long couponId;

    @Schema(description = "券名称")
    private String name;

    @Schema(description = "折扣类型：1-满减 2-每满减 3-折扣 4-无门槛")
    private Integer discountType;

    @Schema(description = "★ 规则描述（服务端拼好，前端直接显示）")
    private String rule;

    @Schema(description = "是否限定范围")
    private Boolean specific;

    @Schema(description = "状态：1-未使用 2-已使用 3-已失效")
    private Integer status;

    @Schema(description = "有效期起")
    private LocalDateTime termBeginTime;

    @Schema(description = "有效期止")
    private LocalDateTime termEndTime;

    @Schema(description = "是否已过期（服务端算好，前端不用比时间）")
    private Boolean expired;
}
