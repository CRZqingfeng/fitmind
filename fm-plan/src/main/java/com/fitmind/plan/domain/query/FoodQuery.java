package com.fitmind.plan.domain.query;

import com.fitmind.common.domain.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 食物库分页查询。
 *
 * <p><b>最容易写错的地方</b>：把"收藏 / 我的 / 搜索"塞进 {@code category} 一个参数里。
 * 一个参数两义，本项目已经踩过（{@code type=4} 同时表示"写笔记"和"笔记被采集"），
 * 表现是"某天开始筛选全空，而代码看起来完全正常"。</p>
 *
 * <p>所以拆成两个维度：{@code scope} 表示**入口/归属**（常见/收藏/我的），
 * {@code category} 只表示**食物分类**（主食/蔬果…）。两者可以同时生效。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "食物库分页查询")
public class FoodQuery extends PageQuery {

    @Schema(description = "入口：common-常见（默认） favorite-我的收藏 mine-我自定义的；不传=全部公共",
            example = "common")
    private String scope;

    @Schema(description = "分类：主食/蔬果/肉蛋奶/豆类坚果/零食饮料/配料", example = "主食")
    private String category;

    @Schema(description = "关键字：食物名或别名模糊匹配", example = "鸡")
    private String keyword;

    @Schema(description = "是否只看公共食物（scope 为空时默认 true）", example = "true")
    private Boolean onlyPublic;
}
