package com.fitmind.learning.domain.query;

import com.fitmind.common.domain.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 问题分页查询（管理端）。
 *
 * <p>⚠️ 与常见实现的差异：常见实现支持按<b>课程名</b>筛选（它调 ES 的
 * {@code searchClient.queryCoursesIdByName}）。我们改为按 {@code courseId} 筛选 ——
 * 为这个非核心筛选项再加一条跨服务契约不划算，前端选课程拿 id 即可。
 * 展示用的课程名仍然会补齐（走 {@code CourseClient}）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "管理端问题分页查询")
public class QuestionAdminPageQuery extends PageQuery {

    @Schema(description = "课程id")
    private Long courseId;

    @Schema(description = "管理端状态：0-未查看 1-已查看")
    private Integer status;

    @Schema(description = "提问时间起")
    private LocalDateTime beginTime;

    @Schema(description = "提问时间止")
    private LocalDateTime endTime;
}
