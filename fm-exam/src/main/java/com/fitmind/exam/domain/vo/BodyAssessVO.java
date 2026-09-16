package com.fitmind.exam.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 体测评估结论。
 *
 * <p>既有"数字"（给用户自己看），也有"分级"（给前端渲染颜色/图标），
 * 还有"建议"（告诉用户接下来怎么办）——
 * 只有数字没有建议，用户拿到 BMI 22.3 也还是不知道该做什么。</p>
 */
@Data
@Accessors(chain = true)
@Schema(description = "体测评估结论")
public class BodyAssessVO {

    @Schema(description = "记录id")
    private Long id;

    @Schema(description = "性别：0-男 1-女 2-未知")
    private Integer gender;

    // ---------- 录入值 ----------
    private BigDecimal height;
    private BigDecimal weight;
    private BigDecimal targetWeight;
    private BigDecimal bodyFatRate;
    private BigDecimal waist;
    private BigDecimal chest;
    private BigDecimal arm;
    private BigDecimal hip;

    // ---------- 计算值（服务端算的） ----------
    @Schema(description = "BMI")
    private BigDecimal bmi;
    @Schema(description = "BMI 分级：1-偏瘦 2-正常 3-超重 4-肥胖（中国标准）")
    private Integer bmiLevel;
    @Schema(description = "BMI 分级文案")
    private String bmiLevelDesc;

    @Schema(description = "体脂分级：1-偏低 2-运动员 3-正常 4-偏高 5-肥胖")
    private Integer fatLevel;
    private String fatLevelDesc;

    @Schema(description = "腰臀比")
    private BigDecimal whr;
    @Schema(description = "腰臀比分级：1-正常 2-中心性肥胖")
    private Integer whrLevel;
    private String whrLevelDesc;

    @Schema(description = "基础代谢 kcal/天（Mifflin-St Jeor）")
    private Integer bmr;

    @Schema(description = "每日总消耗 kcal/天")
    private Integer tdee;

    @Schema(description = "去脂体重 kg")
    private BigDecimal leanMass;

    @Schema(description = "脂肪重量 kg")
    private BigDecimal fatMass;

    @Schema(description = "综合评分 0-100")
    private Integer score;

    // ---------- 结论 ----------
    @Schema(description = "训练目标")
    private Integer goalType;

    @Schema(description = "一句话结论")
    private String summary;

    @Schema(description = "★ 训练建议（热量目标 + 训练侧重 + 风险提示）")
    private String suggestion;

    @Schema(description = "体测时间")
    private LocalDateTime assessTime;
}
