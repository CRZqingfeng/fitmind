package com.fitmind.plan.controller;

import com.fitmind.plan.domain.dto.PlanCreateDTO;
import com.fitmind.plan.domain.dto.PlanUpdateDTO;
import com.fitmind.plan.domain.vo.MyPlanVO;
import com.fitmind.plan.domain.vo.PlanCardVO;
import com.fitmind.plan.domain.vo.PlanDetailVO;
import com.fitmind.plan.domain.vo.TodayWorkoutVO;
import com.fitmind.plan.service.IPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 训练计划接口。
 *
 * <p>网关前缀 {@code /pls/**}，对外是 {@code /pls/plans/**}（网关 StripPrefix=1 去掉 /pls）。
 * 全部需要登录（见 application.yml 的 {@code fm.auth.resource.includeLoginPaths}）。</p>
 *
 * <p>★ {@code /recommend}、{@code /mine}、{@code /today} 三个**字面量**路径写在 {@code /{id}} 之前：
 * Spring 的 PathPatternParser 会优先匹配字面量，但这个顺序写出来更好读 ——
 * 而且如果哪天换成 AntPathMatcher（老项目迁移常见），顺序就真的是必需的。</p>
 */
@RestController
@RequestMapping("/plans")
@RequiredArgsConstructor
@Tag(name = "训练计划接口")
public class PlanController {

    private final IPlanService planService;

    @Operation(summary = "推荐计划列表（官方计划，卡片字段，不含动作明细）")
    @GetMapping("/recommend")
    public List<PlanCardVO> recommend(
            @Parameter(description = "训练目标：增肌 / 减脂；不传 = 全部")
            @RequestParam(value = "goal", required = false) String goal) {
        return planService.queryRecommend(goal);
    }

    @Operation(summary = "我的计划列表（我执行过的计划 + 练到第几练）")
    @GetMapping("/mine")
    public List<MyPlanVO> mine() {
        return planService.queryMine();
    }

    @Operation(summary = "今日训练（三态：无计划 / 今天休息 / 今天要练 + 今日与累计分钟数）")
    @GetMapping("/today")
    public TodayWorkoutVO today() {
        return planService.queryToday();
    }

    @Operation(summary = "计划详情（基本信息 + 训练日 + 每个训练日的动作项）")
    @GetMapping("/{id}")
    public PlanDetailVO detail(
            @Parameter(description = "计划id", example = "1") @PathVariable("id") Long id) {
        return planService.queryDetail(id);
    }

    @Operation(summary = "创建空计划（计划名默认「未命名计划」），返回 planId")
    @PostMapping
    public Long create(@Valid @RequestBody PlanCreateDTO dto) {
        return planService.createPlan(dto);
    }

    @Operation(summary = "编辑计划（改名 / 描述 / 目标 / 难度；不传 = 不改。★ 官方计划不能改）")
    @PutMapping("/{id}")
    public void update(
            @Parameter(description = "计划id", example = "1") @PathVariable("id") Long id,
            @Valid @RequestBody PlanUpdateDTO dto) {
        planService.updatePlan(id, dto);
    }

    @Operation(summary = "执行本计划（幂等：重复调用复用同一条 user_plan 并重置开始日期），返回 userPlanId")
    @PostMapping("/{id}/execute")
    public Long execute(
            @Parameter(description = "计划id", example = "1") @PathVariable("id") Long id) {
        return planService.executePlan(id);
    }

    /**
     * 停止执行这个计划（status → 2 已放弃）。
     *
     * <p>★ 用 {@code planId} 而不是 {@code userPlanId}：{@code uk_user_plan(user_id, plan_id)} 唯一，
     * 所以 (当前用户, planId) 就能定位那一条 —— 前端「我的计划」列表里现成有 planId，
     * 不用为了这个按钮给 {@code MyPlanVO} 再加一个 id 字段（加完两个 id 一定会有人传错那个）。</p>
     *
     * <p>幂等：已经放弃过再调一次返回成功，不报错。</p>
     */
    @Operation(summary = "停止执行计划（幂等；只停自己的、不碰已完成的训练记录）")
    @PutMapping("/{id}/abandon")
    public void abandon(
            @Parameter(description = "计划id", example = "1") @PathVariable("id") Long id) {
        planService.abandonPlan(id);
    }
}
