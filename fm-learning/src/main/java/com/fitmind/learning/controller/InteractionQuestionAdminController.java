package com.fitmind.learning.controller;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.learning.domain.query.QuestionAdminPageQuery;
import com.fitmind.learning.domain.vo.QuestionAdminVO;
import com.fitmind.learning.service.IInteractionQuestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 互动提问接口（管理端）。
 *
 * <p>⚠️ {@code /admin/**} 由 {@code AdminAuthInterceptor} 做角色校验（必须管理员），
 * 常见实现只校验了"登录"。</p>
 */
@RestController
@RequestMapping("/admin/questions")
@RequiredArgsConstructor
@Tag(name = "管理端-互动问答")
public class InteractionQuestionAdminController {

    private final IInteractionQuestionService questionService;

    @Operation(summary = "问题分页（含被隐藏的，匿名返回真实用户）")
    @GetMapping("/page")
    public PageDTO<QuestionAdminVO> queryQuestionPageAdmin(QuestionAdminPageQuery query) {
        return questionService.queryQuestionPageAdmin(query);
    }

    @Operation(summary = "问题详情")
    @GetMapping("/{id}")
    public QuestionAdminVO queryQuestionByIdAdmin(
            @Parameter(description = "问题id", example = "1") @PathVariable("id") Long id) {
        return questionService.queryQuestionByIdAdmin(id);
    }

    @Operation(summary = "隐藏 / 显示问题")
    @PutMapping("/{id}/hidden/{hidden}")
    public void hiddenQuestion(
            @Parameter(description = "问题id", example = "1") @PathVariable("id") Long id,
            @Parameter(description = "true-隐藏 false-显示") @PathVariable("hidden") Boolean hidden) {
        questionService.hiddenQuestion(id, hidden);
    }
}
