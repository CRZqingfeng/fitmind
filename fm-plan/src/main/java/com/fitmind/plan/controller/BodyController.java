package com.fitmind.plan.controller;

import com.fitmind.plan.domain.dto.BodyMetricDTO;
import com.fitmind.plan.domain.vo.BodyMetricVO;
import com.fitmind.plan.service.IBodyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 体征接口（体重 / 预算 / 三大营养素目标）。
 *
 * <p>★ 这里只收 {@code weightKg}（公斤）：<b>不要</b>再收一个"斤"字段。
 * 单位换算只在前端 {@code utils/format.ts} 的 toJin/toKg 一处做，
 * 接口层同时支持两种单位，就一定有人传错。</p>
 */
@RestController
@RequestMapping("/body")
@RequiredArgsConstructor
@Tag(name = "体征接口")
public class BodyController {

    private final IBodyService bodyService;

    @Operation(summary = "记录体重（同一天更新那一行；跨天插新行；同事务算 BMI / 预算 / 三大营养素）")
    @PostMapping("/metrics")
    public BodyMetricVO record(@Valid @RequestBody BodyMetricDTO dto) {
        return bodyService.record(dto);
    }

    @Operation(summary = "当前体征（最新一行；没有则尝试用用户档案兜底，needProfile=true 表示连档案也没有）")
    @GetMapping("/metrics/latest")
    public BodyMetricVO latest() {
        return bodyService.latest();
    }
}
