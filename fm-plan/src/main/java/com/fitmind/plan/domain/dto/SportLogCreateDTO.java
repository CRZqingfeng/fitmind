package com.fitmind.plan.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/**
 * 记一次运动入参。
 *
 * <p>★ 不收 kcal：消耗由后端按 {@code sport.kcal_per_30min × 分钟 / 30} 算。
 * 前端只负责把用户输入的分钟数传上来。</p>
 */
@Data
@Schema(description = "记一次运动")
public class SportLogCreateDTO {

    @Schema(description = "运动日期；不传 = 今天", example = "2026-10-31")
    private LocalDate date;

    @Schema(description = "运动项目id", example = "1")
    @NotNull(message = "运动项目不能为空")
    private Long sportId;

    @Schema(description = "时长（分钟）", example = "30")
    @NotNull(message = "时长不能为空")
    @Min(value = 1, message = "时长最少 1 分钟")
    @Max(value = 600, message = "一次最多记 600 分钟")
    private Integer durationMin;
}
