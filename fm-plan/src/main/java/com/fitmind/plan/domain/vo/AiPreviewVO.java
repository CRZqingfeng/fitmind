package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 预览结果（结果页 4 个区块的全部数据）。
 *
 * <p>★ <b>这个 VO 里没有一个字段是"前端该自己算的"</b>：
 * BMI、占比、达成日期、预算、预计分钟全在服务端算好。
 * 前端只要"照着画"—— 这样页面上的数字和库里的数字永远一致。</p>
 */
@Data
@Schema(description = "AI 定制计划预览")
public class AiPreviewVO {

    @Schema(description = "预览 id（apply 的凭证；刷新后丢失 = 需要重新生成）")
    private String previewId;

    @Schema(description = "训练计划草稿 id")
    private Long planDraftId;

    @Schema(description = "饮食计划草稿 id")
    private Long dietDraftId;

    @Schema(description = "目标推算（① 目标卡）")
    private AiTargetVO target;

    @Schema(description = "评测结果（② 评测卡）")
    private EvaluationVO evaluation;

    @Schema(description = "三大营养素与预算（③ 饮食建议）")
    private MacrosVO macros;

    @Schema(description = "生成的训练计划标题")
    private String planTitle;

    @Schema(description = "训练难度展示串（新手/进阶/专业，来自 plan.difficulty）")
    private String difficultyLabel;

    @Schema(description = "生成的饮食计划标题")
    private String dietPlanTitle;

    @Schema(description = "饮食计划周期（天）")
    private Integer dietCycleDays;

    @Schema(description = "饮食计划预览：每餐 + 食物（③ 饮食预览）")
    private List<com.fitmind.plan.domain.vo.DietPlanDetailVO.DayVO> dietDays;

    @Schema(description = "训练计划预览：每个训练日的动作项（④ 训练预览，形状与计划详情页一致）")
    private List<PlanDayVO> trainDays;

    @Schema(description = "运动分布（由已选动作的 category 反推，不编数字）")
    private DistributionVO distribution;

    @Schema(description = "文案与降级说明")
    private AdviceVO advice;

    /** ② 评测卡 */
    @Data
    @Schema(description = "评测结果")
    public static class EvaluationVO {
        @Schema(description = "身高（cm）", example = "180.0")
        private BigDecimal heightCm;
        @Schema(description = "体重（kg）", example = "65.0")
        private BigDecimal weightKg;
        @Schema(description = "目标体重（kg）", example = "75.0")
        private BigDecimal targetWeightKg;
        @Schema(description = "BMI（1 位小数，与 NutritionCalculator 同口径）", example = "20.1")
        private BigDecimal bmi;
        @Schema(description = "BMI 档位：偏瘦 / 正常 / 偏胖 / 肥胖", example = "正常")
        private String bmiLabel;
        @Schema(description = "BMI 在刻度条上的位置（0~1）", example = "0.41")
        private Double bmiPercent;
    }

    /** ③ 三大营养素 */
    @Data
    @Schema(description = "预算与三大营养素")
    public static class MacrosVO {
        @Schema(description = "每日热量预算（= 4P+4C+9F）", example = "2145")
        private Integer kcalBudget;
        private List<MacroItemVO> items;
    }

    /** 单条营养素 */
    @Data
    @Schema(description = "单条营养素")
    public static class MacroItemVO {
        @Schema(description = "key：protein / carb / fat", example = "protein")
        private String key;
        @Schema(description = "中文名", example = "蛋白")
        private String label;
        @Schema(description = "克数", example = "130.0")
        private BigDecimal gram;
        @Schema(description = "该营养素折算的热量（4/4/9 kcal per g）", example = "520")
        private Integer kcal;
        @Schema(description = "占预算的百分比（整数）", example = "24")
        private Integer percent;
    }

    /** ④ 运动分布 */
    @Data
    @Schema(description = "运动分布（力量/有氧/柔韧占比，和为 1）")
    public static class DistributionVO {
        @Schema(description = "力量占比", example = "0.9")
        private Double strength;
        @Schema(description = "有氧占比", example = "0.05")
        private Double cardio;
        @Schema(description = "柔韧占比", example = "0.05")
        private Double flexibility;
    }

    /** 文案与降级 */
    @Data
    @Schema(description = "文案与降级说明")
    public static class AdviceVO {
        @Schema(description = "饮食建议（AI；降级时为模板句）")
        private String dietAdvice;
        @Schema(description = "训练建议（AI；降级时为模板句）")
        private String trainingAdvice;
        @Schema(description = "目标提示（AI；降级时为模板句）")
        private String targetTip;
        @Schema(description = "true = AI 那一步失败/超时，已用规则骨架；页面应提示「建议稍后重新生成」")
        private Boolean degraded;
        @Schema(description = "服务端的补充说明（例如：动作库胸只有 2 个动作，已全部排入）")
        private List<String> notes;
    }
}
