package com.fitmind.plan.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 记录体重入参。
 *
 * <p><b>为什么 {@code heightCm} 不是 @NotNull</b>：第二次起就不该再问用户身高。
 * 第一次必须传（要算 BMI 与预算），之后可以只传体重，身高沿用上一次 ——
 * 这条规则在 Service 里判断，并给出"第一次记录体重需要填身高"的文案，
 * 而不是让前端在两个页面各写一份"if 没有身高就显示输入框"。</p>
 *
 * <p>★ {@code weightKg} 单位是 <b>kg</b>。前端界面上填的是"斤"，×2 的换算只在
 * `utils/format.ts` 的 toJin/toKg 一处做，不要在这里传"斤"。</p>
 */
@Data
@Schema(description = "记录体重")
public class BodyMetricDTO {

    @Schema(description = "记录日期；不传 = 今天", example = "2026-10-31")
    private LocalDate date;

    @Schema(description = "身高 cm；第一次必填，之后不传则沿用上一次", example = "180")
    @DecimalMin(value = "100.0", message = "身高要在 100~250 厘米之间")
    @DecimalMax(value = "250.0", message = "身高要在 100~250 厘米之间")
    private BigDecimal heightCm;

    @Schema(description = "体重 kg", example = "65.0")
    @NotNull(message = "体重不能为空")
    @DecimalMin(value = "20.0", message = "体重要在 20~300 公斤之间")
    @DecimalMax(value = "300.0", message = "体重要在 20~300 公斤之间")
    private BigDecimal weightKg;

    @Schema(description = "目标体重 kg", example = "70.0")
    private BigDecimal targetWeightKg;

    @Schema(description = "目标日期", example = "2027-03-01")
    private LocalDate targetDate;

    @Schema(description = "训练目标：增肌 / 减脂；不传则沿用上一次，再没有就按增肌", example = "增肌")
    private String goal;
}
