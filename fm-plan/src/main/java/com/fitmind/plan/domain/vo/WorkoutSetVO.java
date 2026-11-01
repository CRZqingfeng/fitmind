package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 某一组的完成情况。
 */
@Data
@Schema(description = "某组的完成情况")
public class WorkoutSetVO {

    @Schema(description = "第几组")
    private Integer setNo;

    @Schema(description = "重量 kg")
    private BigDecimal weightKg;

    @Schema(description = "完成次数（计时型为 0）")
    private Integer reps;

    @Schema(description = "本组时长（计时型）")
    private Integer durationSec;

    @Schema(description = "是否完成：0-否 1-是")
    private Integer done;
}
