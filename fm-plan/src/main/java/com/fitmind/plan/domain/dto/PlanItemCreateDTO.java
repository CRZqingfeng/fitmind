package com.fitmind.plan.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 往训练日批量加动作入参。
 *
 * <p>只收 exerciseIds，组数/次数/组休由后端给默认值（4 组 × 8 次、组休 60 秒）。
 * 为什么不在这个接口就允许传组数：动作库页面只负责"挑哪几个动作"，
 * 具体几组几次是回到编排页后逐个调的事 —— 一次请求里塞两件事，
 * 出错时用户不知道该改哪一步。</p>
 */
@Data
@Schema(description = "批量添加动作入参")
public class PlanItemCreateDTO {

    @Schema(description = "动作id列表（按数组顺序入列）", example = "[1,2,5]")
    @NotEmpty(message = "至少选一个动作")
    @Size(max = 50, message = "一次最多加 50 个动作")
    private List<Long> exerciseIds;
}
