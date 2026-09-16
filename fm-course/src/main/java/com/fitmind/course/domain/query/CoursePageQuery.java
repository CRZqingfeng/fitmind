package com.fitmind.course.domain.query;

import com.fitmind.common.domain.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 课程分页查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "课程查询条件")
public class CoursePageQuery extends PageQuery {

    @Schema(description = "课程名称，模糊匹配", example = "增肌")
    private String name;

    @Schema(description = "课程类型：1-训练计划课 2-单动作教学 3-理论饮食课")
    private Integer courseType;

    @Schema(description = "状态：1-待上架 2-已上架 3-下架 4-已完结")
    private Integer status;

    @Schema(description = "一级分类（训练目标）")
    private Long firstCateId;

    @Schema(description = "课程来源：draft-只看草稿 shelf-只看正式")
    private String source;
}
