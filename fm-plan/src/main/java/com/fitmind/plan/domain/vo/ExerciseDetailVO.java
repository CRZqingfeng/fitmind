package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 动作详情。
 *
 * <p>{@code heat} / {@code lumbarPressure} / {@code gifUrl} 在开源数据集里没有，
 * 这里会返回 null —— 前端显示"—"即可。<b>不要在前端把它们兜成 0</b>：
 * "腰椎压力 0"会被用户读成"这个动作不伤腰"。</p>
 */
@Data
@Schema(description = "动作详情")
public class ExerciseDetailVO {

    @Schema(description = "动作id")
    private Long id;

    @Schema(description = "中文名")
    private String name;

    @Schema(description = "英文名")
    private String enName;

    @Schema(description = "分类")
    private String category;

    @Schema(description = "部位")
    private String bodyPart;

    @Schema(description = "器械")
    private String equipment;

    @Schema(description = "难度 1~5")
    private Integer difficulty;

    @Schema(description = "热量参考；null = 数据集未提供")
    private Integer heat;

    @Schema(description = "腰椎压力 1~5；null = 数据集未提供")
    private Integer lumbarPressure;

    @Schema(description = "示意图")
    private String imageUrl;

    @Schema(description = "动图；null = 数据集未提供")
    private String gifUrl;

    @Schema(description = "主要肌群（原始英文值）")
    private List<String> primaryMuscles;

    @Schema(description = "次要肌群（原始英文值）")
    private List<String> secondaryMuscles;

    @Schema(description = "动作步骤")
    private List<String> steps;

    @Schema(description = "提示；null = 数据集未提供")
    private String tips;
}
