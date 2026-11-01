package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 食物库字典。
 *
 * <p><b>为什么分类要动态取</b>（方案 A）：数据集里根本没有"中式菜肴 / 西式菜肴"，
 * 常量返回 8 类会造出三个点进去永远空着的入口。这里只返回**库里真有数据**的分类，
 * 顺序用固定 rank（{@code DietConstants.FOOD_CATEGORY_ORDER}），保证前端每次看到的一样。</p>
 *
 * <p>★ {@code entries} 明确标出每个左侧入口的 {@code kind}：
 * {@code entry} 是入口（常见/收藏/我的/搜索），{@code category} 是分类。
 * 前端据此决定点它是"改 scope"还是"改 category" —— 不用猜，也不会写错。</p>
 */
@Data
@Schema(description = "食物库字典")
public class FoodDictVO {

    @Schema(description = "分类（只含有数据的，按固定顺序）")
    private List<String> categories;

    @Schema(description = "餐次：1早 2午 3晚 4加餐")
    private List<MealTypeVO> mealTypes;

    @Schema(description = "左侧入口 + 分类的统一列表（kind 区分类型）")
    private List<EntryVO> entries;

    /** 餐次 */
    @Data
    @AllArgsConstructor
    @Schema(description = "餐次")
    public static class MealTypeVO {

        @Schema(description = "1/2/3/4")
        private Integer value;

        @Schema(description = "早餐/午餐/晚餐/加餐")
        private String label;
    }

    /** 左侧入口 */
    @Data
    @AllArgsConstructor
    @Schema(description = "左侧入口")
    public static class EntryVO {

        @Schema(description = "key：scope 用 common/favorite/mine；分类用分类名", example = "common")
        private String key;

        @Schema(description = "展示名", example = "常见")
        private String label;

        @Schema(description = "类型：entry-入口 category-分类", example = "entry")
        private String kind;
    }
}
