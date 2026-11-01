package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 训练日里的一个动作项（详情页 / 执行页共用）。
 *
 * <p>{@code prescription} = "共4组·每组8次·组休60秒" 这类串，由后端拼。
 * 口径（次数型 / 计时型 / 拉伸组）都在 Service 里一处判断完，
 * 前端只负责原样显示 —— 否则改一次文案要动 3 个 .vue。</p>
 */
@Data
@Schema(description = "训练日动作项")
public class PlanDayItemVO {

    @Schema(description = "动作项id")
    private Long id;

    @Schema(description = "动作id")
    private Long exerciseId;

    @Schema(description = "动作名")
    private String exerciseName;

    @Schema(description = "示意图")
    private String imageUrl;

    @Schema(description = "分类")
    private String category;

    @Schema(description = "部位")
    private String bodyPart;

    @Schema(description = "器械")
    private String equipment;

    @Schema(description = "顺序，从 1 开始")
    private Integer sort;

    @Schema(description = "组类型：0-正式 1-热身 2-拉伸")
    private Integer groupType;

    @Schema(description = "组类型标签：正式组 / 热身组 / 拉伸组")
    private String groupTypeLabel;

    @Schema(description = "组数")
    private Integer sets;

    @Schema(description = "每组次数（计时型为 0）")
    private Integer reps;

    @Schema(description = "计时型单组秒数")
    private Integer durationSec;

    @Schema(description = "建议重量 kg")
    private BigDecimal weightKg;

    @Schema(description = "建议重量「斤」（= kg × 2，换算只在此处做一次）")
    private BigDecimal weightJin;

    @Schema(description = "组休秒数")
    private Integer restSec;

    @Schema(description = "展示串：共4组·每组8次·组休60秒")
    private String prescription;
}
