package com.fitmind.learning.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 学习计划页（顶部统计 + 分课计划）。
 */
@Data
@Schema(description = "学习计划统计")
public class LearningPlanPageVO {

    @Schema(description = "本周已完成的训练日/动作数")
    private Integer weekFinished;

    @Schema(description = "本周计划的训练频率总数（所有进行中计划的 week_freq 之和）")
    private Integer weekTotalPlan;

    @Schema(description = "分课计划列表")
    private List<LearningPlanVO> list;
}
