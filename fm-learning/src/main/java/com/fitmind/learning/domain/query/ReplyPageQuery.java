package com.fitmind.learning.domain.query;

import com.fitmind.common.domain.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 回答分页查询。
 *
 * <p>两个参数二选一：传 {@code questionId} 查该问题下的回答；
 * 传 {@code answerId} 查该回答下的评论。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "回答分页查询")
public class ReplyPageQuery extends PageQuery {

    @Schema(description = "问题id（查回答）")
    private Long questionId;

    @Schema(description = "回答id（查评论）")
    private Long answerId;
}
