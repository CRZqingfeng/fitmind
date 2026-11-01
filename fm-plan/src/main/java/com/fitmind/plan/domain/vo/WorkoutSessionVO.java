package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 训练执行页数据（一次 session 的全貌）。
 *
 * <p><b>为什么要把"已完成的组"一起返回</b>：执行页每组打完勾就提交一次，
 * 用户中途刷新、切后台回来，页面必须能恢复"第 3 组已打勾、第 4 组没打"。
 * 只在本地内存里记进度的话，刷新一次全丢 —— 用户会以为白练了。</p>
 */
@Data
@Schema(description = "训练执行页数据")
public class WorkoutSessionVO {

    @Schema(description = "训练id")
    private Long sessionId;

    @Schema(description = "训练日期")
    private LocalDate sessionDate;

    @Schema(description = "状态：0-进行中 1-已完成 2-已放弃")
    private Integer status;

    @Schema(description = "状态标签")
    private String statusLabel;

    @Schema(description = "计划id")
    private Long planId;

    @Schema(description = "计划名")
    private String planTitle;

    @Schema(description = "训练日id")
    private Long planDayId;

    @Schema(description = "训练日名")
    private String planDayName;

    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;

    @Schema(description = "时长秒数")
    private Integer durationSec;

    @Schema(description = "消耗千卡；0 = 未估")
    private Integer kcal;

    @Schema(description = "总容量 = Σ(重量kg × 次数)")
    private BigDecimal totalVolume;

    @Schema(description = "已完成组数")
    private Integer doneSets;

    @Schema(description = "总组数（计划安排的）")
    private Integer totalSets;

    @Schema(description = "动作项（含已完成的组）")
    private List<WorkoutItemVO> items;
}
