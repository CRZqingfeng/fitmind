package com.fitmind.search.domain.query;

import com.fitmind.common.domain.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 课程检索条件。
 *
 * <p>继承 {@link PageQuery} 拿到 pageNo / pageSize，
 * 但<b>没有直接复用它的 sortBy</b>——那个字段是给 MyBatis 排数据库列用的，
 * 而 ES 的排序是它自己的字段体系，语义不同。这里另开一组排序入参避免混淆。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "课程检索条件")
public class CourseSearchQuery extends PageQuery {

    @Schema(description = "搜索关键词，命中课程名/动作名/教练/分类路径", example = "增肌")
    private String keyword;

    @Schema(description = "一级分类（训练目标）")
    private Long categoryIdLv1;

    @Schema(description = "二级分类（训练部位）")
    private Long categoryIdLv2;

    @Schema(description = "三级分类（器械场景）")
    private Long categoryIdLv3;

    @Schema(description = "只看免费", example = "true")
    private Boolean free;

    @Schema(description = "课程类型：1-训练计划课 2-单动作教学 3-理论饮食课")
    private Integer type;

    @Schema(description = "★ 训练目标：1-增肌 2-减脂 3-塑形 4-保持")
    private Integer goalType;

    @Schema(description = "★ 适用水平：1-零基础 2-初级 3-中级 4-高级")
    private Integer trainingLevel;

    @Schema(description = "最低价格，单位分")
    private Integer minPrice;

    @Schema(description = "最高价格，单位分")
    private Integer maxPrice;

    @Schema(description = "上架时间起（含）")
    private String beginTime;

    @Schema(description = "上架时间止（含）")
    private String endTime;

    /**
     * 排序方式。
     *
     * <ul>
     *   <li>{@code default}：综合（相关性 + 报名数加权），不传时的默认</li>
     *   <li>{@code price}：价格</li>
     *   <li>{@code publishTime}：上架时间</li>
     *   <li>{@code sold}：报名人数</li>
     * </ul>
     */
    @Schema(description = "排序：default / price / publishTime / sold", example = "default")
    private String rankBy = "default";

    @Schema(description = "是否升序（仅对 price/publishTime/sold 生效）", example = "false")
    private Boolean rankAsc = false;
}
