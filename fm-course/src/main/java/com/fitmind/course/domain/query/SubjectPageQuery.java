package com.fitmind.course.domain.query;

import com.fitmind.common.domain.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 题目分页查询（教练 / 管理端）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "题目分页查询")
public class SubjectPageQuery extends PageQuery {

    @Schema(description = "课程id")
    private Long courseId;

    @Schema(description = "题干关键字（模糊匹配）")
    private String name;

    @Schema(description = "题型：1-单选 2-多选 3-不定向 4-判断")
    private Integer subjectType;

    @Schema(description = "难易度：1-简单 2-中等 3-困难")
    private Integer difficulty;
}
