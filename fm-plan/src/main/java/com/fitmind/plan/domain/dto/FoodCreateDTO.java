package com.fitmind.plan.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 自定义食物入参。
 *
 * <p><b>为什么允许用户自己建食物</b>：数据集只有 1677 条原料，外卖、餐馆菜、自家做的饭都没有。
 * 不允许自定义的话，用户记"妈妈做的红烧肉"就只能瞎选一个相近的原料，历史数据直接失真。</p>
 *
 * <p>★ {@code units} 最多两项（除默认单位外）—— 与 {@code units_json} 的口径一致，
 * 不做任意单位换算引擎。</p>
 */
@Data
@Schema(description = "自定义食物")
public class FoodCreateDTO {

    @Schema(description = "食物名", example = "妈妈做的红烧肉")
    @NotBlank(message = "食物名不能为空")
    @Size(max = 64, message = "食物名最长 64 个字")
    private String name;

    @Schema(description = "分类（取 foods/dict 返回的分类之一）", example = "中式菜肴")
    @NotBlank(message = "分类不能为空")
    private String category;

    @Schema(description = "每 100g 热量", example = "280")
    @NotNull(message = "热量不能为空")
    @DecimalMin(value = "0", message = "热量不能为负")
    private Integer kcalPer100g;

    @Schema(description = "每 100g 蛋白（克）", example = "12.0")
    private BigDecimal proteinPer100g;

    @Schema(description = "每 100g 碳水（克）", example = "8.0")
    private BigDecimal carbPer100g;

    @Schema(description = "每 100g 脂肪（克）", example = "22.0")
    private BigDecimal fatPer100g;

    @Schema(description = "可选单位（不含「克」，最多 2 个）", example = "[{\"unit\":\"碗\",\"gram\":200}]")
    @Size(max = 2, message = "除默认单位外最多再加 2 个单位")
    private List<UnitItem> units;

    @Schema(description = "emoji（可选）", example = "🍖")
    private String emoji;

    /** 一个可选单位 */
    @Data
    @Schema(description = "可选单位")
    public static class UnitItem {

        @Schema(description = "单位名", example = "碗")
        private String unit;

        @Schema(description = "1 个该单位等于多少克", example = "200")
        private BigDecimal gram;
    }
}
