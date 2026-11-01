package com.fitmind.plan.domain.query;

import com.fitmind.common.domain.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 动作库分页查询。
 *
 * <p>三个筛选条件是 **AND** 关系（设计稿里左侧分类 + 右侧器械 chips 可以同时生效）。</p>
 *
 * <p>★ {@code category} 与 {@code bodyPart} 是两个维度：
 * 前 3 个分类（热身/拉伸/有氧）走 category，11 个部位走 bodyPart。
 * 所以两个参数都留着，不要合并成"分类"一个字段 —— 合并之后"拉伸 + 腿"这种组合就查不了。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "动作库分页查询")
public class ExerciseQuery extends PageQuery {

    @Schema(description = "分类：热身 / 拉伸 / 有氧（力量类请用 bodyPart 筛）", example = "力量")
    private String category;

    @Schema(description = "部位：胸/背/腿/肩/二头/三头/小腿/前臂/臀部/腹部/斜方肌", example = "胸")
    private String bodyPart;

    @Schema(description = "器械：徒手/哑铃/杠铃/固定器械/弹力带/其他", example = "哑铃")
    private String equipment;

    @Schema(description = "关键字：中文名或英文名模糊匹配", example = "卧推")
    private String keyword;

    @Schema(description = "是否只查上架的（默认 true；管理端才传 false）", example = "true")
    private Boolean onlyOnline = true;
}
