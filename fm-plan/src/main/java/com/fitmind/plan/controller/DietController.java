package com.fitmind.plan.controller;

import com.fitmind.plan.domain.dto.DietLogCreateDTO;
import com.fitmind.plan.domain.vo.DietLogCreatedVO;
import com.fitmind.plan.domain.vo.DietPlanCardVO;
import com.fitmind.plan.domain.vo.DietPlanDetailVO;
import com.fitmind.plan.domain.vo.DietTodayVO;
import com.fitmind.plan.service.IDietService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 饮食接口（今日汇总 / 饮食记录 / 饮食计划）。
 *
 * <p>★ 与 {@code /plans/today} <b>刻意是两个接口</b>：训练域和饮食域的刷新频率不同，
 * 合成一个"大今日"的后果是记一条饮食要连带重算训练进度，慢且容易一起失败。</p>
 */
@RestController
@RequestMapping("/diet")
@RequiredArgsConstructor
@Tag(name = "饮食接口")
public class DietController {

    private final IDietService dietService;

    @Operation(summary = "今日汇总（摄入 / 预算 / 还可以吃 / 三大营养素 / 四餐明细）")
    @GetMapping("/today")
    public DietTodayVO today(
            @Parameter(description = "日期，不传 = 今天", example = "2026-10-31")
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return dietService.queryToday(date);
    }

    @Operation(summary = "推荐饮食计划（卡片字段，不含餐次）")
    @GetMapping("/plans/recommend")
    public List<DietPlanCardVO> recommend() {
        return dietService.queryRecommend();
    }

    @Operation(summary = "饮食计划详情（每天 + 每餐 + 食物；热量按食物现价实时算）")
    @GetMapping("/plans/{id}")
    public DietPlanDetailVO planDetail(
            @Parameter(description = "饮食计划id", example = "1") @PathVariable("id") Long id,
            @Parameter(description = "第几天，不传 = 全部返回", example = "1")
            @RequestParam(value = "day", required = false) Integer day) {
        return dietService.queryPlanDetail(id, day);
    }

    @Operation(summary = "记一份食物（同一天同一餐同一食物同一单位 → 合并，六列一起累加）")
    @PostMapping("/logs")
    public DietLogCreatedVO createLog(@Valid @RequestBody DietLogCreateDTO dto) {
        return dietService.createLog(dto);
    }

    @Operation(summary = "删一条饮食记录")
    @DeleteMapping("/logs/{id}")
    public void removeLog(
            @Parameter(description = "记录id", example = "1") @PathVariable("id") Long id) {
        dietService.removeLog(id);
    }
}
