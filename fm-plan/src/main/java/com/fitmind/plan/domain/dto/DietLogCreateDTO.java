package com.fitmind.plan.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 记一份食物入参。
 *
 * <p><b>为什么 {@code unit} 是必填而不是"后端默认取 food.defaultUnit"</b>：
 * 份量选择是用户在产品里明确做的一步（"1 碗"还是"50 克"），
 * 由后端偷偷补默认值会让用户以为自己选了，实际记的是 100g。</p>
 *
 * <p>★ 前端**不要**传 kcal —— 热量由后端按 {@code food.kcal_per_100g × 折算克数} 算。
 * 让前端传热量，等于把"算得对不对"交给客户端，而这份数据要进历史。</p>
 */
@Data
@Schema(description = "记一份食物")
public class DietLogCreateDTO {

    @Schema(description = "记录日期；不传 = 今天", example = "2026-10-31")
    private LocalDate date;

    @Schema(description = "餐次：1早餐 2午餐 3晚餐 4加餐", example = "1")
    @NotNull(message = "餐次不能为空")
    @Min(value = 1, message = "餐次只能是 1~4")
    @Max(value = 4, message = "餐次只能是 1~4")
    private Integer mealType;

    @Schema(description = "食物id", example = "1")
    @NotNull(message = "食物id不能为空")
    private Long foodId;

    @Schema(description = "份量数值", example = "50")
    @NotNull(message = "份量不能为空")
    @DecimalMin(value = "0.1", message = "份量要大于 0")
    private BigDecimal amount;

    @Schema(description = "单位，必须是该食物 units_json 里列出的（如 克 / 碗）", example = "克")
    @NotNull(message = "单位不能为空")
    @Size(max = 8, message = "单位最长 8 个字")
    private String unit;
}
