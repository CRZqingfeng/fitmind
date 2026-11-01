package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 目标推算结果（结果页 ① 目标卡 的数据源）。
 *
 * <p>★ 所有数字都由服务端算好，前端<b>不算日期、不算差值</b>：
 * 一旦前端也有一份算法，就会和折线、预算在不同时间点算出不同的数（本项目踩过：C35）。</p>
 */
@Data
@Schema(description = "目标推算：当前体重 → 目标体重、预计达成日、7 天折线")
public class AiTargetVO {

    @Schema(description = "当前体重（kg；库里一律 kg，界面 ×2 显示斤）", example = "65.0")
    private BigDecimal currentWeightKg;

    @Schema(description = "目标体重（kg）；null = 用户没填目标", example = "75.0")
    private BigDecimal targetWeightKg;

    @Schema(description = "当前 BMI（保留 1 位）", example = "20.1")
    private BigDecimal currentBmi;

    @Schema(description = "推算的每周变化量（kg/周，已 clamp 到 0.25~0.75）", example = "0.25")
    private BigDecimal weeklyDeltaKg;

    @Schema(description = "还要多少天；0 = 已达成；null = 缺目标体重，无法推算", example = "280")
    private Integer daysToGoal;

    @Schema(description = "预计达成日（yyyy-MM-dd）；null = 无法推算", example = "2027-08-08")
    private LocalDate targetDate;

    @Schema(description = "未来 7 天折线（永远 7 个点；缺目标时是水平线）")
    private List<ForecastPoint> forecast;

    @Schema(description = "true = 连体重都没有，前端应引导去「记录体重」而不是显示 0", example = "false")
    private Boolean needProfile;

    @Schema(description = "true = 数据来自 fm-user 档案兜底（本地没起 fm-user 时为 false）", example = "false")
    private Boolean fromProfile;

    /** 折线上的一个点 */
    @Data
    @Schema(description = "折线点")
    public static class ForecastPoint {

        @Schema(description = "日期", example = "2026-11-01")
        private LocalDate date;

        @Schema(description = "预计体重（kg，1 位小数）", example = "65.0")
        private BigDecimal weightKg;

        public ForecastPoint() {
        }

        public ForecastPoint(LocalDate date, BigDecimal weightKg) {
            this.date = date;
            this.weightKg = weightKg;
        }
    }
}
