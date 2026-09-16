package com.fitmind.promotion.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 待下单的课程（算优惠时的输入）。
 */
@Data
@Accessors(chain = true)
@Schema(description = "待下单课程")
public class OrderCourseDTO {

    @Schema(description = "课程id")
    private Long id;

    @Schema(description = "课程分类id（用于判断券的限定范围）")
    private Long categoryId;

    @Schema(description = "课程单价（分）")
    private Integer price;
}
