package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 接受预览后的结果。
 *
 * <p>★ {@code userPlanId} 非空 = 已经真的"加入我的计划"了（建了 user_plan 行），
 * 所以前端接受后可以直接跳首页看"今日训练"。幂等：重复 apply 返回同样的三个 id。</p>
 */
@Data
@Schema(description = "接受 AI 计划的结果")
public class AiApplyVO {

    @Schema(description = "已发布的训练计划 id", example = "41")
    private Long planId;

    @Schema(description = "user_plan 行 id（= 执行本计划）", example = "23")
    private Long userPlanId;

    @Schema(description = "已发布的饮食计划 id", example = "17")
    private Long dietPlanId;
}
