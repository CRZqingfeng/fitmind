package com.fitmind.user.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 身体档案展示对象 ★。
 */
@Data
@Accessors(chain = true)
@Schema(description = "身体档案")
public class BodyProfileVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "身高（cm）")
    private BigDecimal height;

    @Schema(description = "当前体重（kg）")
    private BigDecimal weight;

    @Schema(description = "目标体重（kg）")
    private BigDecimal targetWeight;

    @Schema(description = "BMI（后端自动计算）")
    private BigDecimal bmi;

    @Schema(description = "体脂率（%）")
    private BigDecimal bodyFatRate;

    @Schema(description = "腰围（cm）")
    private BigDecimal waist;

    @Schema(description = "胸围（cm）")
    private BigDecimal chest;

    @Schema(description = "臂围（cm）")
    private BigDecimal arm;

    @Schema(description = "臀围（cm）")
    private BigDecimal hip;

    @Schema(description = "训练目标：1-增肌，2-减脂，3-塑形，4-保持")
    private Integer goalType;

    @Schema(description = "训练目标名称")
    private String goalTypeName;

    @Schema(description = "训练水平：1-零基础，2-初级，3-中级，4-高级")
    private Integer trainingLevel;

    @Schema(description = "训练水平名称")
    private String trainingLevelName;

    @Schema(description = "每周可训练次数")
    private Integer weeklyFrequency;

    @Schema(description = "伤病史")
    private String injuryHistory;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
