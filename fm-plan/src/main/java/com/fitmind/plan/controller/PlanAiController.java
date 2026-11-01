package com.fitmind.plan.controller;

import com.fitmind.plan.domain.dto.AiApplyDTO;
import com.fitmind.plan.domain.dto.AiPreviewDTO;
import com.fitmind.plan.domain.vo.AiApplyVO;
import com.fitmind.plan.domain.vo.AiPreviewVO;
import com.fitmind.plan.domain.vo.AiTargetVO;
import com.fitmind.plan.service.IPlanAiService;
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
 * AI 定制计划接口。
 *
 * <p>★ <b>单独一个 Controller 而不是塞进 {@link PlanController}</b>：
 * {@code PlanController} 里有 {@code @GetMapping("/{id}")}，
 * 把 {@code /plans/ai/target} 混进去就得依赖"字面量优先于路径变量"这条隐性约定
 * （PathPatternParser 确实优先，但换 AntPathMatcher 就不是了）。
 * 拆开后 {@code /plans/ai/**}（3 段）与 {@code /plans/{id}}（2 段）段数就不同，天然不撞。</p>
 *
 * <p>三个接口都是登录态：{@code /plans/**} 已在
 * {@code fm.auth.resource.includeLoginPaths} 里，匿名请求会被 SDK 拦成 401。</p>
 */
@RestController
@RequestMapping("/plans/ai")
@RequiredArgsConstructor
@Tag(name = "AI 定制计划接口")
public class PlanAiController {

    private final IPlanAiService planAiService;

    @Operation(summary = "目标推算（当前 → 目标、预计达成日、7 天折线）")
    @GetMapping("/target")
    public AiTargetVO target() {
        return planAiService.queryTarget();
    }

    /**
     * <p>★ {@code required = false}：整个 body 不传 = 全用规则默认值。
     * 这不是"放宽校验"，而是让"aigc 那一步挂了"时前端仍然能只调这一个接口出计划 ——
     * 降级链的最后一环就在这儿。</p>
     */
    @Operation(summary = "生成计划预览（写两份草稿；草稿不会出现在「我的计划」里）")
    @PostMapping("/preview")
    public AiPreviewVO preview(@Valid @RequestBody(required = false) AiPreviewDTO dto) {
        return planAiService.preview(dto == null ? new AiPreviewDTO() : dto);
    }

    @Operation(summary = "接受预览（草稿发布 + 立刻加入我的计划；幂等）")
    @PostMapping("/apply")
    public AiApplyVO apply(@Valid @RequestBody AiApplyDTO dto) {
        return planAiService.apply(dto.getPreviewId());
    }
}
