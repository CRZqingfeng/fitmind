package com.fitmind.plan.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 生成预览的入参 —— 就是 fm-aigc `POST /ais/plan/skeleton` 的出参原样回传。
 *
 * <p><b>本类最容易写错的地方：把"全部可空"当成"随便传"。</b>
 * 可空是为了让 aigc 挂掉时仍能出计划（全用规则默认）；
 * 但一旦传了值，就必须被校验/夹紧 —— 这些值<b>来自模型输出</b>，
 * 完全可能是 {@code weeklyFrequency=99}、比例和 = 0.9。</p>
 *
 * <p>★ 这里<b>不接收任何 id</b>（除了 {@code skeletonId} 这种纯日志用的字符串）：
 * 动作、食物、计划 id 全部由服务端选。模型输出里出现 id 字段，
 * 到这里连解析的机会都没有（DTO 里根本没有这些字段）。</p>
 *
 * <p>★ {@code skeletonId} <b>不是权限凭证</b>：它只用于日志串联。
 * 真正的凭证是 {@code previewId}（服务端生成的草稿指针，apply 时按它验归属）。</p>
 */
@Data
@Schema(description = "生成 AI 计划预览的入参（全部可空，空 = 用规则默认）")
public class AiPreviewDTO {

    @Schema(description = "骨架 id（fm-aigc 生成，仅用于日志串联）")
    @Size(max = 64, message = "骨架 id 过长")
    private String skeletonId;

    @Schema(description = "分化方式：三分化 / 五分化 / 上下肢分化；非法值按周频回落", example = "三分化")
    @Size(max = 16, message = "分化方式最长 16 个字")
    private String division;

    @Schema(description = "每周训练次数 1~7；越界会被 clamp", example = "4")
    private Integer weeklyFrequency;

    @Schema(description = "强度：LOW / MEDIUM / HIGH；非法值按目标回落", example = "MEDIUM")
    @Size(max = 16, message = "强度最长 16 个字符")
    private String intensity;

    @Schema(description = "每餐热量占比（顺序即餐次顺序，同一 mealType 出现多次即多顿加餐）")
    @Valid
    @Size(max = 8, message = "最多 8 餐")
    private List<MealSplitDTO> mealSplit;

    @Schema(description = "AI 饮食建议（仅展示，超长截断）")
    @Size(max = 240, message = "饮食建议过长")
    private String dietAdvice;

    @Schema(description = "AI 训练建议（仅展示）")
    @Size(max = 240, message = "训练建议过长")
    private String trainingAdvice;

    @Schema(description = "AI 目标提示（仅展示）")
    @Size(max = 240, message = "目标提示过长")
    private String targetTip;

    @Schema(description = "AI 那一步是否降级（仅展示与日志）")
    private Boolean degraded;

    /** 一餐的热量占比 */
    @Data
    @Schema(description = "一餐的热量占比")
    public static class MealSplitDTO {

        @Schema(description = "餐次：1早餐 2午餐 3晚餐 4加餐", example = "1")
        @Min(value = 1, message = "餐次只能是 1~4")
        @Max(value = 4, message = "餐次只能是 1~4")
        private Integer mealType;

        @Schema(description = "该餐占全天预算的比例（0~1）", example = "0.25")
        private Double ratio;
    }
}
