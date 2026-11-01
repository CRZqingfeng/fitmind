package com.fitmind.learning.domain.query;

import com.fitmind.common.domain.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 笔记分页查询（用户端）。
 *
 * <p>★ <b>M10-1 第 3 期加了 {@code scope}</b>，与 {@link QuestionPageQuery} 同一套语义：
 * 不带 = 必须给 course/section（行为不变）；{@code scope=ALL} = 全站广场。</p>
 *
 * <p>★★ <b>全站模式下隐私笔记的边界</b>：公共列表走的是
 * {@code NoteMapper.queryNotePageBySectionId}，那条 SQL 里已经有
 * {@code AND (n.is_private = 0 OR n.user_id = #{userId})} ——
 * 也就是**别人的私有笔记本来就不会出现**。这一点必须靠这条 SQL 守住，
 * 而不是靠"调用方记得过滤"（验收里有一条负向断言专门盯它）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "笔记分页查询")
public class NotePageQuery extends PageQuery {

    /** {@code scope} 的取值：全站广场 */
    public static final String SCOPE_ALL = "ALL";

    @Schema(description = "课程id")
    private Long courseId;

    @Schema(description = "训练动作id")
    private Long sectionId;

    @Schema(description = "是否只看我的")
    private Boolean onlyMine;

    @Schema(description = "浏览范围：不传=按 course/section 过滤（默认，二者必须有一个）；"
            + "ALL=全站广场（忽略 course/section）", example = "ALL")
    private String scope;

    /** 是否全站广场模式 */
    public boolean isAllScope() {
        return SCOPE_ALL.equalsIgnoreCase(scope);
    }
}
