package com.fitmind.learning.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 签到结果。
 */
@Data
@Schema(description = "签到结果")
public class SignResultVO {

    @Schema(description = "连续签到天数")
    private Integer signDays;

    @Schema(description = "本次签到奖励积分（不含基础分）")
    private Integer rewardPoints;

    @Schema(description = "本次合计得分（基础分 + 奖励）")
    private Integer totalPoints;
}
