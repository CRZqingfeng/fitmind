package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 食物（列表项，饮食库卡片用）。
 *
 * <p>★ {@code units} 决定"点＋之后能选哪几个单位"。前端不许自己写死"克/碗/盒"——
 * 每个食物支持的单位不同（鸡蛋有"个"，牛奶有"盒"，米饭有"碗"）。</p>
 */
@Data
@Schema(description = "食物")
public class FoodVO {

    @Schema(description = "食物id")
    private Long id;

    @Schema(description = "食物名")
    private String name;

    @Schema(description = "别名")
    private String alias;

    @Schema(description = "分类")
    private String category;

    @Schema(description = "每 100g 热量")
    private Integer kcalPer100g;

    @Schema(description = "每 100g 蛋白")
    private BigDecimal proteinPer100g;

    @Schema(description = "每 100g 碳水")
    private BigDecimal carbPer100g;

    @Schema(description = "每 100g 脂肪")
    private BigDecimal fatPer100g;

    @Schema(description = "展示用 emoji")
    private String emoji;

    @Schema(description = "默认单位")
    private String defaultUnit;

    @Schema(description = "可选单位（含默认单位）")
    private List<UnitVO> units;

    @Schema(description = "我是否收藏了它")
    private Boolean favorite;

    @Schema(description = "是否我自定义的")
    private Boolean mine;

    @Schema(description = "热量展示串：46 千卡/100g，后端拼好")
    private String kcalText;

    /** 一个单位 */
    @Data
    @Schema(description = "单位")
    public static class UnitVO {

        @Schema(description = "单位名", example = "碗")
        private String unit;

        @Schema(description = "1 个该单位 = 多少克", example = "150")
        private BigDecimal gram;
    }
}
