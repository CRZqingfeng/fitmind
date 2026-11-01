package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 计划卡片（推荐列表 / 我的计划列表）。
 *
 * <p><b>这里刻意不含动作明细</b>：列表里有 10 个计划，每个计划 5 个训练日、
 * 每天 5 个动作 —— 为了渲染卡片上的"一周5练 · 增肌"去查 250 条动作，
 * 就是典型的 N+1。动作明细只在详情接口里给。</p>
 */
@Data
@Schema(description = "计划卡片")
public class PlanCardVO {

    @Schema(description = "计划id")
    private Long id;

    @Schema(description = "计划名")
    private String title;

    @Schema(description = "一句话描述")
    private String summary;

    @Schema(description = "封面图")
    private String coverUrl;

    @Schema(description = "目标：增肌 / 减脂")
    private String goal;

    @Schema(description = "难度值 0/1/2")
    private Integer difficulty;

    @Schema(description = "难度标签：新手 / 进阶 / 专业")
    private String difficultyLabel;

    @Schema(description = "一周几练")
    private Integer trainDaysPerWeek;

    @Schema(description = "预估千卡；0 = 暂无估算")
    private Integer kcalEstimate;

    @Schema(description = "器械清单")
    private List<String> equipment;

    @Schema(description = "来源：0-官方 1-自建 2-AI")
    private Integer source;

    @Schema(description = "卡片副标题，后端拼好：一周5练 · 增肌 · 进阶")
    private String subtitle;
}
