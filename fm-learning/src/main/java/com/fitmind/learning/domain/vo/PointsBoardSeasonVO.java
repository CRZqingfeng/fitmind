package com.fitmind.learning.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

/**
 * 赛季信息。
 */
@Data
@Schema(description = "赛季信息")
public class PointsBoardSeasonVO {

    @Schema(description = "赛季id（查历史榜时传它）")
    private Integer id;

    @Schema(description = "赛季名称")
    private String name;

    @Schema(description = "开始日期")
    private LocalDate beginTime;

    @Schema(description = "结束日期")
    private LocalDate endTime;
}
