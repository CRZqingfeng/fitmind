package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 当前体征（首页「记录体重」入口用）。
 *
 * <p>★ 只有 {@code weightKg}，<b>没有 weightJin</b>：斤是展示单位，
 * 换算只在前端 {@code utils/format.ts} 的 toJin/toKg 一处做。
 * 接口返回两个单位的话，一定会有某处用错。</p>
 */
@Data
@Schema(description = "体征")
public class BodyMetricVO {

    @Schema(description = "记录日期；来自用户档案兜底时是 null")
    private LocalDate recordDate;

    @Schema(description = "身高 cm")
    private BigDecimal heightCm;

    @Schema(description = "体重 kg（界面显示斤由前端 ×2）")
    private BigDecimal weightKg;

    @Schema(description = "目标体重 kg")
    private BigDecimal targetWeightKg;

    @Schema(description = "目标日期")
    private LocalDate targetDate;

    @Schema(description = "BMI；没身高时为 null")
    private BigDecimal bmi;

    @Schema(description = "每日热量预算")
    private Integer kcalBudget;

    @Schema(description = "蛋白目标（克）")
    private BigDecimal proteinG;

    @Schema(description = "碳水目标（克）")
    private BigDecimal carbG;

    @Schema(description = "脂肪目标（克）")
    private BigDecimal fatG;

    @Schema(description = "是否还缺档案（true 时前端应弹「记录体重」）")
    private Boolean needProfile;

    @Schema(description = "是否来自用户档案兜底（不是本模块自己记的）")
    private Boolean fromProfile;
}
