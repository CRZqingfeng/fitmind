package com.fitmind.learning.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 今日积分统计（按类型汇总）。
 */
@Data
@Schema(description = "今日积分统计")
public class PointsStatisticsVO {

    @Schema(description = "积分类型：1-学习 2-签到 3-问答 4-笔记")
    private Integer type;

    @Schema(description = "类型描述")
    private String typeDesc;

    @Schema(description = "今日该类型已得积分")
    private Integer points;

    @Schema(description = "该类型每日上限（0=不限）")
    private Integer maxPoints;
}
