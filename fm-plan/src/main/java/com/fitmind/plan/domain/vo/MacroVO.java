package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 三大营养素进度（碳水 / 蛋白 / 脂肪）。
 *
 * <p>★ {@code current} 与 {@code target} 都由后端算好：摄入来自当天 diet_log 的合计，
 * 目标来自 user_body_metric。前端**不参与任何计算** ——
 * 前端算一份、后端算一份的下场是两个数在演示时对不上，而用户会以为是你算错了。</p>
 */
@Data
@AllArgsConstructor
@Schema(description = "营养素进度")
public class MacroVO {

    @Schema(description = "键：carb / protein / fat", example = "carb")
    private String key;

    @Schema(description = "展示名", example = "碳水")
    private String label;

    @Schema(description = "已摄入（克）", example = "120.0")
    private BigDecimal current;

    @Schema(description = "目标（克）", example = "324.0")
    private BigDecimal target;

    @Schema(description = "单位", example = "g")
    private String unit;
}
