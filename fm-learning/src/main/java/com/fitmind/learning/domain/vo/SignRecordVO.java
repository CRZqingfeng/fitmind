package com.fitmind.learning.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 本月签到记录（由 Redis 位图还原）。
 */
@Data
@Schema(description = "本月签到记录")
public class SignRecordVO {

    @Schema(description = "本月已签到的日期（几号）")
    private List<Integer> days;

    @Schema(description = "连续签到天数（含跨月续接）")
    private Integer continuousDays;
}
