package com.fitmind.learning.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 榜单条目。
 */
@Data
@Schema(description = "榜单条目")
public class PointsBoardItemVO {

    @Schema(description = "排名（从 1 开始）")
    private Integer rank;

    @Schema(description = "用户名")
    private String name;

    @Schema(description = "积分")
    private Integer points;
}
