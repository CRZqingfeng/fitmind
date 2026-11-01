package com.fitmind.plan.controller;

import com.fitmind.plan.domain.dto.PlanDayCreateDTO;
import com.fitmind.plan.domain.dto.PlanDayRenameDTO;
import com.fitmind.plan.domain.dto.PlanItemCreateDTO;
import com.fitmind.plan.service.IPlanDayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 训练日 / 动作项编排接口。
 *
 * <p><b>为什么和 {@link PlanController} 拆成两个类，但路径前缀都是 {@code /plans}</b>：
 * 一个类里塞 9 个接口、200 多行，改训练日的代码要滑过计划详情的一大段。
 * 拆开只是文件组织，路径仍按设计稿（{@code /plans/{id}/days} 与 {@code /plans/days/{dayId}}），
 * 不要为了"一个 Controller 一个前缀"把路径改成 {@code /plan-days/**} —— 那样前端路由就散了。</p>
 */
@RestController
@RequestMapping("/plans")
@RequiredArgsConstructor
@Tag(name = "训练日编排接口")
public class PlanDayController {

    private final IPlanDayService planDayService;

    @Operation(summary = "批量添加训练日（入参 weekdays，如 [1,2,3,4,5]；已存在的星期自动跳过）")
    @PostMapping("/{planId}/days")
    public void addDays(
            @Parameter(description = "计划id", example = "1") @PathVariable("planId") Long planId,
            @Valid @RequestBody PlanDayCreateDTO dto) {
        planDayService.addDays(planId, dto.getWeekdays());
    }

    @Operation(summary = "训练日重命名（不传 name = 保持原值）")
    @PutMapping("/days/{dayId}")
    public void renameDay(
            @Parameter(description = "训练日id", example = "1") @PathVariable("dayId") Long dayId,
            @Valid @RequestBody PlanDayRenameDTO dto) {
        planDayService.renameDay(dayId, dto.getName());
    }

    @Operation(summary = "往训练日批量加动作（默认 4 组 × 8 次，组休 60 秒）")
    @PostMapping("/days/{dayId}/items")
    public void addItems(
            @Parameter(description = "训练日id", example = "1") @PathVariable("dayId") Long dayId,
            @Valid @RequestBody PlanItemCreateDTO dto) {
        planDayService.addItems(dayId, dto.getExerciseIds());
    }

    @Operation(summary = "删除训练日里的一个动作项（自动重排序号、重算预计时长）")
    @DeleteMapping("/days/{dayId}/items/{itemId}")
    public void removeItem(
            @Parameter(description = "训练日id", example = "1") @PathVariable("dayId") Long dayId,
            @Parameter(description = "动作项id", example = "1") @PathVariable("itemId") Long itemId) {
        planDayService.removeItem(dayId, itemId);
    }

    @Operation(summary = "删除训练日（连同动作项；★ 已经练过的那一天不允许删，会返回 400 + 说明）")
    @DeleteMapping("/days/{dayId}")
    public void deleteDay(
            @Parameter(description = "训练日id", example = "1") @PathVariable("dayId") Long dayId) {
        planDayService.deleteDay(dayId);
    }
}
