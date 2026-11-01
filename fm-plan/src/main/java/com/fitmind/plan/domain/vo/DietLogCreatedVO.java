package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 记一条饮食记录的结果。
 *
 * <p><b>为什么要返回 {@code merged} 与 {@code totalAmount}</b>：
 * 同一天同一餐同一食物再记一次是**合并**（口径 7），不是新增。
 * 如果接口只返回一个 logId，用户会以为"又加了一条"，而页面上数字只涨了一点 ——
 * 反过来，如果后端不说，前端也没法提示"已累加为 100g"。</p>
 *
 * <p>⚠️ 这个值**只用于弹层即时反馈**，首页的总数仍然重新拉 {@code /diet/today}
 * （口径 8）。前端拿 {@code kcal} 去改总数，就会和后端的四舍五入口径分叉。</p>
 */
@Data
@Schema(description = "记饮食结果")
public class DietLogCreatedVO {

    @Schema(description = "记录id（删除时用）")
    private Long logId;

    @Schema(description = "本次这一份的热量")
    private Integer kcal;

    @Schema(description = "本次份量")
    private BigDecimal amount;

    @Schema(description = "单位")
    private String unit;

    @Schema(description = "本次折算克数")
    private BigDecimal gram;

    @Schema(description = "是否与已有记录合并了")
    private Boolean merged;

    @Schema(description = "合并后这一行的总份量（未合并时等于本次份量）")
    private BigDecimal totalAmount;

    @Schema(description = "给用户看的提示，后端拼好：已累加为 100g / 已记 50g")
    private String message;
}
