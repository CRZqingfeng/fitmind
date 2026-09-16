package com.fitmind.learning.domain.query;

import com.fitmind.common.domain.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 问题分页查询（用户端）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "问题分页查询")
public class QuestionPageQuery extends PageQuery {

    @Schema(description = "课程id")
    private Long courseId;

    @Schema(description = "训练动作id")
    private Long sectionId;

    @Schema(description = "是否只看我提问的")
    private Boolean onlyMine;
}
