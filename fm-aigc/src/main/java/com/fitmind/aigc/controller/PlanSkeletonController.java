package com.fitmind.aigc.controller;

import com.fitmind.aigc.domain.dto.PlanSkeletonDTO;
import com.fitmind.aigc.domain.vo.PlanSkeletonVO;
import com.fitmind.aigc.service.IPlanSkeletonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 定制计划骨架接口（网关前缀 {@code /ais}，对外是 {@code /ais/plan/skeleton}）。
 *
 * <p>★ <b>为什么要单独开一个接口，而不是让用户在聊天里说"帮我定制计划"</b>：
 * 这个接口的返回是一段<b>给前端解析的 JSON</b>，不是给人看的话。
 * 如果它被聊天入口复用，用户会在屏幕上看到一整段裸 JSON。
 * 所以它只从这里进，并且 {@code AgentType.parse()} 里明确排除了 PLAN。</p>
 *
 * <p>★ <b>登录态</b>：{@code fm.auth.resource.includeLoginPaths} 里必须包含 {@code /plan/**}
 * （见 application.yml）。漏了这条不会报错，而是让所有匿名请求都以"没有档案"的身份进来，
 * 症状是"所有人点定制都被提示去填档案"—— 和 P1 漏配 {@code /diet/**} 是同一个坑。</p>
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/plan")
@RequiredArgsConstructor
@Tag(name = "AI 定制计划")
public class PlanSkeletonController {

    private final IPlanSkeletonService planSkeletonService;

    /**
     * <p>★ {@code required = false}：整个 body 不传也能出骨架（全用档案/默认值）。
     * 前端第一次进来只想知道"能不能定制"，不该因为少传一个字段就 400。</p>
     */
    @Operation(summary = "生成计划骨架（分化/周频/强度/餐次比例 + 三段文案；不含任何 id）")
    @PostMapping("/skeleton")
    public PlanSkeletonVO skeleton(@Valid @RequestBody(required = false) PlanSkeletonDTO dto) {
        return planSkeletonService.skeleton(dto == null ? new PlanSkeletonDTO() : dto);
    }
}
