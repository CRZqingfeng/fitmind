package com.fitmind.exam.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * 体测趋势（首次 vs 最新）。
 *
 * <p><b>★ 这是体测模块最被用户需要的输出</b>：
 * 一次体测只能说明"我现在什么样"，两次之间的差值才说明"我练得有没有效果"。
 * 体重降了 2kg 是好事，但如果去脂体重也降了，那减的是肌肉不是脂肪 ——
 * 所以趋势里<b>同时给体重和体脂的变化</b>，让用户看到数字背后的东西。</p>
 *
 * <p>各项变化量统一为"最新 − 首次"，负数表示下降。
 * 接口里会额外说明哪个方向是好的，避免用户自己猜。</p>
 */
@Data
@Accessors(chain = true)
@Schema(description = "体测趋势（首次 vs 最新）")
public class BodyTrendVO {

    @Schema(description = "首次体测")
    private BodyAssessVO first;

    @Schema(description = "最新体测")
    private BodyAssessVO latest;

    @Schema(description = "体重变化（kg，负数=下降）")
    private BigDecimal weightChange;

    @Schema(description = "体脂率变化（%，负数=下降）")
    private BigDecimal fatRateChange;

    @Schema(description = "腰围变化（cm，负数=下降）")
    private BigDecimal waistChange;

    @Schema(description = "综合评分变化（正数=变好）")
    private Integer scoreChange;

    @Schema(description = "间隔天数")
    private Long days;

    @Schema(description = "体测次数")
    private Integer times;
}
