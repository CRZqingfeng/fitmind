package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 今日训练（首页那张卡）。
 *
 * <p><b>三种状态必须分开</b>（不是一个 boolean 能表达的）：
 * <ul>
 *   <li>{@code status=0} 没有执行的计划 —— 卡片要引导去挑计划</li>
 *   <li>{@code status=1} 有计划但今天没安排 —— 文案是"休息也是训练的一部分"</li>
 *   <li>{@code status=2} 今天要练 —— 显示训练日名 + 动作数 + 开始训练</li>
 * </ul>
 * 把 0 和 1 合并成 {@code hasPlan=false} 的后果：用户明明在执行计划，
 * 首页却一直喊"还没有执行任何计划"，然后他真的又去执行了一遍。</p>
 *
 * <p>{@code todayMinutes / totalMinutes} 也放在这里，是因为首页那两个数字
 * 跟"今天有没有训练"必须**同一次请求**返回 —— 分两个接口会出现
 * "卡片说今天练了，数字还是昨天的"。</p>
 */
@Data
@Accessors(chain = true)
@Schema(description = "今日训练")
public class TodayWorkoutVO {

    @Schema(description = "是否有正在执行的计划")
    private Boolean hasPlan;

    @Schema(description = "状态：0-无计划 1-今天休息 2-有训练")
    private Integer status;

    @Schema(description = "状态标签：还没有计划 / 今天休息 / 今天要练")
    private String statusLabel;

    @Schema(description = "给用户看的下一步提示（后端拼，前端直接显示）")
    private String tips;

    @Schema(description = "计划id")
    private Long planId;

    @Schema(description = "计划名")
    private String planTitle;

    @Schema(description = "训练日id（status=2 才有）")
    private Long planDayId;

    @Schema(description = "训练日名，如「周一练胸」")
    private String dayName;

    @Schema(description = "第几练")
    private Integer dayIndex;

    @Schema(description = "动作数")
    private Integer exerciseCount;

    @Schema(description = "预计分钟（后端算）")
    private Integer estMinutes;

    @Schema(description = "今日已训练分钟（Σ 今天已完成 session 的 duration_sec ÷ 60）")
    private Integer todayMinutes;

    @Schema(description = "累计训练分钟（Σ 全部已完成 session 的 duration_sec ÷ 60）")
    private Integer totalMinutes;

    @Schema(description = "今天这一练的 sessionId（有则前端可直接「继续/查看」）")
    private Long sessionId;

    @Schema(description = "今天这一练是否已完成")
    private Boolean sessionFinished;
}
