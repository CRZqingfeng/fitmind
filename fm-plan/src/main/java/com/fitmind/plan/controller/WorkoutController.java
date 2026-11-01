package com.fitmind.plan.controller;

import com.fitmind.plan.domain.dto.WorkoutSetDTO;
import com.fitmind.plan.domain.dto.WorkoutStartDTO;
import com.fitmind.plan.domain.vo.WorkoutSessionVO;
import com.fitmind.plan.service.IWorkoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 训练执行接口。
 *
 * <p>★ 设计上的关键点：**每组打完勾就 PUT 一次**，不是等全部做完再提交。
 * 用户中途接电话退出、切后台被系统杀、刷新页面 —— 只要每组都落库了，回来就能接着练。</p>
 */
@RestController
@RequestMapping("/workouts")
@RequiredArgsConstructor
@Tag(name = "训练执行接口")
public class WorkoutController {

    private final IWorkoutService workoutService;

    @Operation(summary = "开始今日训练（重复调用返回已存在的 session），返回 sessionId")
    @PostMapping
    public Long start(@Valid @RequestBody WorkoutStartDTO dto) {
        return workoutService.start(dto);
    }

    @Operation(summary = "训练页数据：训练日 + 动作项 + 已完成的组（刷新后可恢复打勾状态）")
    @GetMapping("/{sessionId}")
    public WorkoutSessionVO detail(
            @Parameter(description = "训练id", example = "1") @PathVariable("sessionId") Long sessionId) {
        return workoutService.querySession(sessionId);
    }

    @Operation(summary = "记录一组（同一动作项 + 组号覆盖，重复提交不会多出一组）")
    @PutMapping("/{sessionId}/sets")
    public void logSet(
            @Parameter(description = "训练id", example = "1") @PathVariable("sessionId") Long sessionId,
            @Valid @RequestBody WorkoutSetDTO dto) {
        workoutService.logSet(sessionId, dto);
    }

    @Operation(summary = "取消打勾（删一组；后面的组号自动前移。★ 已完成的训练不能改）")
    @DeleteMapping("/{sessionId}/items/{itemId}/sets/{setNo}")
    public void removeSet(
            @Parameter(description = "训练id", example = "1") @PathVariable("sessionId") Long sessionId,
            @Parameter(description = "动作项id", example = "1") @PathVariable("itemId") Long itemId,
            @Parameter(description = "组号，从 1 开始", example = "2") @PathVariable("setNo") Integer setNo) {
        workoutService.removeSet(sessionId, itemId, setNo);
    }

    @Operation(summary = "完成训练（写 duration_sec / total_volume / status=1；重复调用幂等）")
    @PostMapping("/{sessionId}/finish")
    public void finish(
            @Parameter(description = "训练id", example = "1") @PathVariable("sessionId") Long sessionId) {
        workoutService.finish(sessionId);
    }
}
