package com.fitmind.promotion.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

/**
 * 一个"用券方案"的计算结果。
 */
@Data
@Accessors(chain = true)
@Schema(description = "优惠方案")
public class CouponDiscountDTO {

    @Schema(description = "本次使用的用户券id")
    private List<Long> ids;

    @Schema(description = "规则描述")
    private List<String> rules;

    @Schema(description = "总优惠金额（分）")
    private Integer discountAmount;

    /**
     * 每门课分摊到多少优惠：key = courseId，value = 优惠金额（分）。
     *
     * <p>为什么需要这个：订单明细要记录每门课实付多少，
     * 而优惠是按券算的、不是按课算的 —— 必须有一个分摊规则。</p>
     */
    @Schema(description = "每门课分摊的优惠")
    private Map<Long, Integer> discountDetail;
}
