package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 训练日（详情页里的一组）。
 *
 * <p>{@code summary} 就是设计稿上那行 "7个动作·预计43分钟" ——
 * <b>由后端拼</b>。前端自己拼的话，空训练日会显示"0个动作·预计0分钟"，
 * 而文案本该是"还没有动作，去添加"。</p>
 */
@Data
@Schema(description = "训练日")
public class PlanDayVO {

    @Schema(description = "训练日id")
    private Long id;

    @Schema(description = "第几练，从 1 开始")
    private Integer dayIndex;

    @Schema(description = "星期几 1~7，null=不绑定")
    private Integer weekday;

    @Schema(description = "星期标签：周一…周日 / 未指定")
    private String weekdayLabel;

    @Schema(description = "训练日名")
    private String name;

    @Schema(description = "动作数")
    private Integer itemCount;

    @Schema(description = "预计分钟（后端算）")
    private Integer estMinutes;

    @Schema(description = "标题右侧摘要：7个动作·预计43分钟 / 还没有动作")
    private String summary;

    @Schema(description = "动作项列表")
    private List<PlanDayItemVO> items;
}
