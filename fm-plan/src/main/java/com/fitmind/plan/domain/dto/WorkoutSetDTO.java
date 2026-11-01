package com.fitmind.plan.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 记录一组入参。
 *
 * <p><b>为什么 reps / weightKg 不设 @NotNull</b>：两者都有合理默认值 ——
 * 重量默认取计划里的建议重量，次数默认取计划里的 reps。
 * 用户在训练页只点"完成"不填数字是常态（徒手动作更没有重量），
 * 强制填数字会让执行页变得没法用。</p>
 */
@Data
@Schema(description = "记录一组入参")
public class WorkoutSetDTO {

    @Schema(description = "动作项id（plan_day_item.id）", example = "1")
    @NotNull(message = "动作项id不能为空")
    private Long itemId;

    @Schema(description = "第几组，从 1 开始", example = "1")
    @NotNull(message = "组号不能为空")
    @Min(value = 1, message = "组号从 1 开始")
    private Integer setNo;

    @Schema(description = "本组重量 kg（不传则用计划里的建议重量）", example = "20.00")
    private BigDecimal weightKg;

    @Schema(description = "本组次数（不传则用计划里的次数）", example = "8")
    @Min(value = 0, message = "次数不能为负")
    private Integer reps;

    @Schema(description = "计时型本组秒数（平板/跳绳）", example = "60")
    @Min(value = 0, message = "时长不能为负")
    private Integer durationSec;
}
