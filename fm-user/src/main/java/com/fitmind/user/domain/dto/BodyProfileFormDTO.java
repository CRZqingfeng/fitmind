package com.fitmind.user.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 身体档案表单 ★。
 *
 * <p>注意这里<b>没有 bmi 字段</b>——BMI 由后端根据身高体重计算，前端传了也会被忽略，
 * 避免出现「身高体重改了、BMI 还是旧值」的脏数据。</p>
 */
@Data
@Schema(description = "身体档案表单")
public class BodyProfileFormDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "身高（cm）", example = "182.0")
    private BigDecimal height;

    @Schema(description = "当前体重（kg）", example = "64.0")
    private BigDecimal weight;

    @Schema(description = "目标体重（kg）", example = "72.0")
    private BigDecimal targetWeight;

    @Schema(description = "体脂率（%）", example = "14.0")
    private BigDecimal bodyFatRate;

    @Schema(description = "腰围（cm）")
    private BigDecimal waist;

    @Schema(description = "胸围（cm）")
    private BigDecimal chest;

    @Schema(description = "臂围（cm）")
    private BigDecimal arm;

    @Schema(description = "臀围（cm）")
    private BigDecimal hip;

    @Schema(description = "训练目标：1-增肌，2-减脂，3-塑形，4-保持", example = "1")
    private Integer goalType;

    @Schema(description = "训练水平：1-零基础，2-初级，3-中级，4-高级", example = "1")
    private Integer trainingLevel;

    @Schema(description = "每周可训练次数", example = "3")
    private Integer weeklyFrequency;

    @Schema(description = "伤病史", example = "无")
    private String injuryHistory;
}
