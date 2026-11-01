package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

/**
 * 我的计划（"我的计划" tab 用）。
 *
 * <p>为什么单独一个 VO：卡片要展示的不是"计划长什么样"，而是**"我练到哪了"** ——
 * 开始日期、练到第几练、已完成几天。这些字段 plan 表里没有，硬塞进 {@link PlanCardVO}
 * 会让"推荐计划"的接口也返回一堆 null。</p>
 */
@Data
@Schema(description = "我的计划")
public class MyPlanVO {

    @Schema(description = "计划id")
    private Long id;

    @Schema(description = "计划名")
    private String title;

    @Schema(description = "封面")
    private String coverUrl;

    @Schema(description = "目标")
    private String goal;

    @Schema(description = "一周几练")
    private Integer trainDaysPerWeek;

    @Schema(description = "计划一共几个训练日")
    private Integer dayCount;

    @Schema(description = "我已经练完几天（去重后的训练日数）")
    private Integer finishedDays;

    @Schema(description = "我的执行状态：0-进行中 1-已完成 2-已放弃")
    private Integer status;

    @Schema(description = "状态标签")
    private String statusLabel;

    @Schema(description = "开始日期")
    private LocalDate startDate;

    @Schema(description = "当前练到第几练")
    private Integer currentDayIndex;

    @Schema(description = "进度串：已练 3/5 天（后端拼，前端不重算）")
    private String progressText;
}
