package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 记运动的结果。
 *
 * <p><b>为什么要把 kcal 返回</b>：弹层里"本次消耗 XXX 千卡"要立刻显示。
 * 但前端**不能拿它去改首页的总数** —— 首页的三个数字一律重新拉
 * {@code /diet/today}（口径 8）。返回这个值只是省掉一次额外请求。</p>
 */
@Data
@AllArgsConstructor
@Schema(description = "记运动结果")
public class SportLogResultVO {

    @Schema(description = "记录id")
    private Long logId;

    @Schema(description = "本次消耗（千卡）")
    private Integer kcal;

    @Schema(description = "展示串：30 分钟 · 约 260 千卡")
    private String text;
}
