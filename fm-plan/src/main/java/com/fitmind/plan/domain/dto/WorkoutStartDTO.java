package com.fitmind.plan.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 开始今日训练入参。
 *
 * <p>为什么不收 planId：训练日是唯一的入口（一个训练日一定属于某个计划），
 * 多传一个 planId 就多一处"两个 id 不一致"的校验，而它换不来任何能力。</p>
 */
@Data
@Schema(description = "开始训练入参")
public class WorkoutStartDTO {

    @Schema(description = "训练日id", example = "1")
    @NotNull(message = "训练日id不能为空")
    private Long planDayId;
}
