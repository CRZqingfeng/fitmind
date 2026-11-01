package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 运动字典。
 *
 * <p>左侧 15 项 = 4 个入口（常见/搜索/自定义/动作库）+ 11 个分类。
 * 分类**动态取库里 distinct**（理由同 {@link FoodDictVO}）：
 * 写死 11 类的话，某一类一条数据都没有时，用户点进去是空的。</p>
 *
 * <p>★ {@code 动作库} 这个入口是跳页面（去 {@code /plan/exercise-lib}），
 * 不是筛选条件 —— 所以它的 kind 是 {@code link}，前端据此决定"跳转"还是"改筛选"。</p>
 */
@Data
@Schema(description = "运动字典")
public class SportDictVO {

    @Schema(description = "分类（只含有数据的，按固定顺序）")
    private List<String> categories;

    @Schema(description = "左侧入口 + 分类的统一列表")
    private List<EntryVO> entries;

    /** 左侧入口 */
    @Data
    @AllArgsConstructor
    @Schema(description = "左侧入口")
    public static class EntryVO {

        @Schema(description = "key", example = "running")
        private String key;

        @Schema(description = "展示名", example = "跑步")
        private String label;

        @Schema(description = "类型：entry-入口 link-跳页面 category-分类", example = "category")
        private String kind;
    }
}
