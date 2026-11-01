package com.fitmind.aigc.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 计划骨架（AI 只出这一份东西）。
 *
 * <p>★ 里面<b>没有任何 id</b>，也没有动作名/食物名/克数 —— 那些由 fm-plan 从库里选。
 * 这个 VO 就是"语言 + 骨架选择"的边界。</p>
 */
@Data
@Schema(description = "AI 计划骨架")
public class PlanSkeletonVO {

    @Schema(description = "骨架 id（uuid，仅用于日志串联，不是权限凭证）")
    private String skeletonId;

    @Schema(description = "true = 连体重都没有，前端应引导去补档案/记体重，而不是显示骨架")
    private Boolean needProfile;

    @Schema(description = "true = AI 那一步失败/输出不合规，已回落为规则骨架")
    private Boolean degraded;

    @Schema(description = "分化方式：三分化 / 五分化 / 上下肢分化", example = "三分化")
    private String division;

    @Schema(description = "每周训练次数 1~7", example = "4")
    private Integer weeklyFrequency;

    @Schema(description = "强度：LOW / MEDIUM / HIGH", example = "MEDIUM")
    private String intensity;

    @Schema(description = "每餐热量占比（顺序即餐次顺序）")
    private List<MealSplitVO> mealSplit;

    @Schema(description = "饮食建议")
    private String dietAdvice;

    @Schema(description = "训练建议")
    private String trainingAdvice;

    @Schema(description = "目标提示")
    private String targetTip;

    @Data
    @Schema(description = "一餐的热量占比")
    public static class MealSplitVO {
        @Schema(description = "餐次：1早餐 2午餐 3晚餐 4加餐", example = "1")
        private Integer mealType;
        @Schema(description = "占比（所有项之和 = 1）", example = "0.25")
        private Double ratio;

        public MealSplitVO() {
        }

        public MealSplitVO(Integer mealType, Double ratio) {
            this.mealType = mealType;
            this.ratio = ratio;
        }
    }
}
