package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 计划详情。
 *
 * <p>详情页三个指标卡（一周X练 / X 千卡 / 难度）里的数字全部来自后端字段，
 * 前端不重新计算；"7个动作·预计43分钟"这种串也是后端在 {@link PlanDayVO} 里拼好给的。</p>
 */
@Data
@Schema(description = "计划详情")
public class PlanDetailVO {

    @Schema(description = "计划id")
    private Long id;

    @Schema(description = "计划名")
    private String title;

    @Schema(description = "描述")
    private String summary;

    @Schema(description = "大封面")
    private String coverUrl;

    @Schema(description = "目标")
    private String goal;

    @Schema(description = "难度值")
    private Integer difficulty;

    @Schema(description = "难度标签")
    private String difficultyLabel;

    @Schema(description = "一周几练")
    private Integer trainDaysPerWeek;

    @Schema(description = "预估千卡；0 = 暂无估算（数据集缺 heat，P1 再接）")
    private Integer kcalEstimate;

    @Schema(description = "器械清单")
    private List<String> equipment;

    @Schema(description = "来源：0-官方 1-自建 2-AI")
    private Integer source;

    @Schema(description = "是否官方计划")
    private Boolean official;

    @Schema(description = "当前用户能否编辑（官方计划 = false）")
    private Boolean editable;

    @Schema(description = "当前用户对这个计划的状态：null-没执行过 0-进行中 1-已完成 2-已放弃")
    private Integer userPlanStatus;

    @Schema(description = "我执行的实例id（没执行过为 null，前端据此显示按钮文案）")
    private Long userPlanId;

    @Schema(description = "训练日列表")
    private List<PlanDayVO> days;
}
