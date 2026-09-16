package com.fitmind.learning.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建/修改学习计划请求。
 */
@Data
@Schema(description = "学习计划请求")
public class LearningPlanDTO {

    @Schema(description = "课程id", example = "1")
    @NotNull(message = "课程id不能为空")
    private Long courseId;

    @Schema(description = "每周训练频率", example = "4")
    @NotNull(message = "每周训练频率不能为空")
    @Min(value = 1, message = "每周训练频率需在 1~7 之间")
    @Max(value = 7, message = "每周训练频率需在 1~7 之间")
    private Integer freq;
}
