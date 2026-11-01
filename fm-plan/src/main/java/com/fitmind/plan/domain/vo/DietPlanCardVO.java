package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 饮食计划卡片（推荐列表用，不含餐次明细）。
 */
@Data
@Schema(description = "饮食计划卡片")
public class DietPlanCardVO {

    @Schema(description = "计划id")
    private Long id;

    @Schema(description = "计划名")
    private String title;

    @Schema(description = "描述")
    private String summary;

    @Schema(description = "封面")
    private String coverUrl;

    @Schema(description = "目标")
    private String goal;

    @Schema(description = "一周期几天")
    private Integer cycleDays;

    @Schema(description = "每日热量")
    private Integer kcalPerDay;

    @Schema(description = "副标题：一周期5天 · 增肌 · 2500千卡")
    private String subtitle;

    @Schema(description = "器械/关键词标签（暂无，留空数组给前端统一渲染）")
    private List<String> tags;
}
