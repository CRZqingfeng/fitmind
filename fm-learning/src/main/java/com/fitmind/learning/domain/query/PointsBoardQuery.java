package com.fitmind.learning.domain.query;

import com.fitmind.common.domain.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 积分榜查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "积分榜查询")
public class PointsBoardQuery extends PageQuery {

    @Schema(description = "赛季id；不传或传 0 表示查当前赛季（Redis 实时榜）")
    private Long season;
}
