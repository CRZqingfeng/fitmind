package com.fitmind.learning.domain.query;

import com.fitmind.common.domain.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 笔记分页查询（管理端）。
 *
 * <p>与问答管理端一致：按 {@code courseId} 筛选而不是课程名（不为可选筛选项加跨服务契约）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "管理端笔记分页查询")
public class NoteAdminPageQuery extends PageQuery {

    @Schema(description = "课程id")
    private Long courseId;

    @Schema(description = "是否被折叠：true-已折叠 false-正常")
    private Boolean hidden;

    @Schema(description = "创建时间起")
    private LocalDateTime beginTime;

    @Schema(description = "创建时间止")
    private LocalDateTime endTime;
}
