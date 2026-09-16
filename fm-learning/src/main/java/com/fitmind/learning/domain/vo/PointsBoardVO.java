package com.fitmind.learning.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 积分榜页（含我的排名）。
 */
@Data
@Schema(description = "积分榜")
public class PointsBoardVO {

    @Schema(description = "我的积分")
    private Integer points;

    @Schema(description = "我的排名（未上榜为 null）")
    private Integer rank;

    @Schema(description = "榜单列表")
    private List<PointsBoardItemVO> boardList;
}
