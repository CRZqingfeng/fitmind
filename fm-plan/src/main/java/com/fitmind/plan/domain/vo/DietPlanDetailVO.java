package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 饮食计划详情。
 *
 * <p><b>热量口径</b>（口径 6）：{@code day.kcalTotal} 与 {@code meal.kcal} 是冗余，
 * 真相在食物项。但**详情页展示时**按 {@code food 现价 × amount} 实时算（见 item.kcal），
 * 这样运营改了食物库之后详情页立刻正确；{@code day/meal} 的冗余列只用于对账。</p>
 */
@Data
@Schema(description = "饮食计划详情")
public class DietPlanDetailVO {

    @Schema(description = "计划id")
    private Long id;

    @Schema(description = "计划名")
    private String title;

    @Schema(description = "描述")
    private String summary;

    @Schema(description = "封面")
    private String coverUrl;

    @Schema(description = "目标：增肌 / 减脂")
    private String goal;

    @Schema(description = "一周期几天")
    private Integer cycleDays;

    @Schema(description = "每日热量")
    private Integer kcalPerDay;

    @Schema(description = "三指标里的周期展示串，如「5天 一周期」")
    private String cycleText;

    @Schema(description = "每天的内容")
    private List<DayVO> days;

    /** 第 N 天 */
    @Data
    @Schema(description = "第 N 天")
    public static class DayVO {

        @Schema(description = "第几天")
        private Integer dayIndex;

        @Schema(description = "当天热量（= 各餐之和，实时算）")
        private Integer kcalTotal;

        @Schema(description = "蛋白（克）")
        private java.math.BigDecimal proteinG;

        @Schema(description = "碳水（克）")
        private java.math.BigDecimal carbG;

        @Schema(description = "脂肪（克）")
        private java.math.BigDecimal fatG;

        @Schema(description = "餐次")
        private List<MealVO> meals;
    }

    /** 一餐 */
    @Data
    @Schema(description = "一餐")
    public static class MealVO {

        @Schema(description = "餐次：1早 2午 3晚 4加餐")
        private Integer mealType;

        @Schema(description = "同餐次第几顿（加餐 1/2/3）")
        private Integer mealIndex;

        @Schema(description = "展示名：早餐 / 加餐①")
        private String label;

        @Schema(description = "建议时间 HH:mm（后端切好，前端不要再切）")
        private String mealTime;

        @Schema(description = "这一餐热量")
        private Integer kcal;

        @Schema(description = "食物")
        private List<ItemVO> items;
    }

    /** 一份食物 */
    @Data
    @Schema(description = "一餐里的食物")
    public static class ItemVO {

        @Schema(description = "食物id")
        private Long foodId;

        @Schema(description = "食物名")
        private String foodName;

        @Schema(description = "emoji")
        private String emoji;

        @Schema(description = "份量")
        private java.math.BigDecimal amount;

        @Schema(description = "单位")
        private String unit;

        @Schema(description = "折算克数")
        private java.math.BigDecimal gram;

        @Schema(description = "热量（按 food 现价实时算）")
        private Integer kcal;

        @Schema(description = "份量展示串：50g / 1 碗", example = "50g")
        private String amountText;
    }
}
