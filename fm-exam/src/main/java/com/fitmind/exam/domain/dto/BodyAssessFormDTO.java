package com.fitmind.exam.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 体测提交表单。
 *
 * <p><b>★ 这里只有"量出来的原始数据"，没有任何计算字段</b> ——
 * BMI、体脂分级、基础代谢、综合评分全部由服务端算。
 * 如果接受前端传一个 bmi 过来，改个请求体就能把自己的 BMI 说成正常，
 * 那评估就失去了意义（与退款金额、支付金额"服务端取值"是同一个原则）。</p>
 *
 * <p>除身高体重外的项都允许为空 —— 体脂秤不是人人都有，
 * <b>缺项只是"该指标不参与评估"，不该挡住整次体测</b>。</p>
 */
@Data
@Schema(description = "体测提交")
public class BodyAssessFormDTO {

    @Schema(description = "身高（cm）")
    @NotNull(message = "身高不能为空")
    @DecimalMin(value = "50", message = "身高不合法")
    @DecimalMax(value = "250", message = "身高不合法")
    private BigDecimal height;

    @Schema(description = "体重（kg）")
    @NotNull(message = "体重不能为空")
    @DecimalMin(value = "20", message = "体重不合法")
    @DecimalMax(value = "300", message = "体重不合法")
    private BigDecimal weight;

    @Schema(description = "目标体重（kg，选填）")
    private BigDecimal targetWeight;

    @Schema(description = "体脂率（%，选填）")
    @DecimalMin(value = "1", message = "体脂率不合法")
    @DecimalMax(value = "70", message = "体脂率不合法")
    private BigDecimal bodyFatRate;

    @Schema(description = "腰围（cm，选填）")
    private BigDecimal waist;

    @Schema(description = "胸围（cm，选填）")
    private BigDecimal chest;

    @Schema(description = "臂围（cm，选填）")
    private BigDecimal arm;

    @Schema(description = "臀围（cm，选填）")
    private BigDecimal hip;

    @Schema(description = "训练目标：1-增肌 2-减脂 3-塑形 4-保持")
    private Integer goalType;

    @Schema(description = "体测时间（选填，留空=现在；支持补录）")
    private LocalDateTime assessTime;
}
