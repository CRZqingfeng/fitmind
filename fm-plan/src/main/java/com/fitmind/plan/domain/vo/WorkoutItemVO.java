package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 执行页里的一个动作（含每组打勾状态）。
 */
@Data
@Schema(description = "执行页动作")
public class WorkoutItemVO {

    @Schema(description = "动作项id")
    private Long itemId;

    @Schema(description = "动作id")
    private Long exerciseId;

    @Schema(description = "动作名")
    private String exerciseName;

    @Schema(description = "示意图")
    private String imageUrl;

    @Schema(description = "分类")
    private String category;

    @Schema(description = "组类型：0-正式 1-热身 2-拉伸")
    private Integer groupType;

    @Schema(description = "组类型标签")
    private String groupTypeLabel;

    @Schema(description = "组数")
    private Integer sets;

    @Schema(description = "每组次数")
    private Integer reps;

    @Schema(description = "计时型单组秒数")
    private Integer durationSec;

    @Schema(description = "建议重量 kg（前端显示斤时 ×2）")
    private BigDecimal weightKg;

    @Schema(description = "组休秒数（倒计时用这个，不要前端写死 60）")
    private Integer restSec;

    @Schema(description = "展示串：共4组·每组8次·组休60秒")
    private String prescription;

    @Schema(description = "已完成组数")
    private Integer doneSets;

    @Schema(description = "每组的完成情况")
    private List<WorkoutSetVO> setLogs;
}
