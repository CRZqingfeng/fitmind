package com.fitmind.learning.controller;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.learning.domain.dto.QuestionFormDTO;
import com.fitmind.learning.domain.query.QuestionPageQuery;
import com.fitmind.learning.domain.vo.QuestionVO;
import com.fitmind.learning.service.IInteractionQuestionService;
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
 * 互动提问接口（用户端）。
 *
 * <p>网关前缀 {@code /ls/**}，对外是 {@code /ls/questions/**}，需登录。</p>
 */
@RestController
@RequestMapping("/questions")
@RequiredArgsConstructor
@Tag(name = "互动问答相关接口")
public class InteractionQuestionController {

    private final IInteractionQuestionService questionService;

    @Operation(summary = "提问")
    @PostMapping
    public void saveQuestion(@Valid @RequestBody QuestionFormDTO dto) {
        questionService.saveQuestion(dto);
    }

    @Operation(summary = "修改问题（仅本人）")
    @PutMapping("/{id}")
    public void updateQuestion(
            @Parameter(description = "问题id", example = "1") @PathVariable("id") Long id,
            @Valid @RequestBody QuestionFormDTO dto) {
        questionService.updateQuestion(id, dto);
    }

    @Operation(summary = "问题分页（课程 / 动作至少传一个）")
    @GetMapping("/page")
    public PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query) {
        return questionService.queryQuestionPage(query);
    }

    @Operation(summary = "问题详情")
    @GetMapping("/{id}")
    public QuestionVO queryQuestionById(
            @Parameter(description = "问题id", example = "1") @PathVariable("id") Long id) {
        return questionService.queryQuestionById(id);
    }

    @Operation(summary = "删除问题（连带其下回答与评论）")
    @DeleteMapping("/{id}")
    public void deleteQuestion(
            @Parameter(description = "问题id", example = "1") @PathVariable("id") Long id) {
        questionService.deleteQuestion(id);
    }
}
