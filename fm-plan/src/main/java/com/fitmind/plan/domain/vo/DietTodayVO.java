package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 饮食首页（今日汇总）。
 *
 * <p><b>为什么"还可以吃"由后端算</b>（口径 4）：它 = 预算 − 摄入，**读时算、不落库**。
 * 落库就有三处要同步（记餐、删餐、改预算），必然有一天不一致；
 * 而交给前端算，就等于让"还可以吃"依赖客户端的四舍五入方式。</p>
 *
 * <p>★ {@code intakeKcal / budgetKcal / remainKcal} 三个数字**必须同时返回**：
 * 少任何一个，前端就得自己用另外两个推，那就又是两份实现。</p>
 *
 * <p>★ {@code remainKcal} 可以是<b>负数</b>（吃超了）。不要在这里 max(0)——
 * 返回 0 会让人以为"刚好达标"，而实际超出了 300 千卡。</p>
 */
@Data
@Schema(description = "饮食今日汇总")
public class DietTodayVO {

    @Schema(description = "日期")
    private LocalDate date;

    @Schema(description = "今日摄入（千卡）")
    private Integer intakeKcal;

    @Schema(description = "每日预算（千卡）；0 = 还没记录体重，没有预算")
    private Integer budgetKcal;

    @Schema(description = "还可以吃 = 预算 − 摄入；**可以为负**")
    private Integer remainKcal;

    @Schema(description = "当前体重（kg）；界面显示斤由前端 ×2")
    private BigDecimal weightKg;

    @Schema(description = "BMI；没有身高时为 null")
    private BigDecimal bmi;

    @Schema(description = "目标体重（kg）")
    private BigDecimal targetWeightKg;

    @Schema(description = "运动消耗（千卡）。★ 只展示，不抵扣预算（口径 1）")
    private Integer sportKcal;

    @Schema(description = "关于运动消耗的说明文案，由后端拼好")
    private String sportNote;

    @Schema(description = "是否缺少体征档案（true 时 budgetKcal=0，前端应引导去记录体重）")
    private Boolean needProfile;

    @Schema(description = "三大营养素进度")
    private List<MacroVO> macros;

    @Schema(description = "四餐明细（早/午/晚/加餐，固定 4 条，没记的也给空壳）")
    private List<MealVO> meals;

    /** 一餐 */
    @Data
    @Schema(description = "一餐")
    public static class MealVO {

        @Schema(description = "餐次：1早 2午 3晚 4加餐")
        private Integer mealType;

        @Schema(description = "餐次名：早餐 / 午餐 / 晚餐 / 加餐")
        private String label;

        @Schema(description = "这一餐的热量合计")
        private Integer kcal;

        @Schema(description = "这一餐记了什么")
        private List<ItemVO> items;
    }

    /** 一条饮食记录 */
    @Data
    @Schema(description = "饮食记录")
    public static class ItemVO {

        @Schema(description = "记录id（删除用）")
        private Long id;

        @Schema(description = "食物名（快照）")
        private String foodName;

        @Schema(description = "份量")
        private BigDecimal amount;

        @Schema(description = "单位")
        private String unit;

        @Schema(description = "折算克数")
        private BigDecimal gram;

        @Schema(description = "热量（快照）")
        private Integer kcal;

        @Schema(description = "emoji")
        private String emoji;
    }
}
