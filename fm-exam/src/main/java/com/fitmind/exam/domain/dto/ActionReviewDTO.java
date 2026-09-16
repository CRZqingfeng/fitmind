package com.fitmind.exam.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 教练给动作考核评分。
 */
@Data
@Schema(description = "动作考核评分")
public class ActionReviewDTO {

    @Schema(description = "考核记录id")
    @NotNull(message = "考核记录id不能为空")
    private Long id;

    @Schema(description = "评分 0-100")
    @NotNull(message = "评分不能为空")
    @Min(value = 0, message = "评分必须在 0~100 之间")
    @Max(value = 100, message = "评分必须在 0~100 之间")
    private Integer score;

    @Schema(description = "评语（选填）")
    private String comment;
}
