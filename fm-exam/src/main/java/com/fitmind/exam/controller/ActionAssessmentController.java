package com.fitmind.exam.controller;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.domain.query.PageQuery;
import com.fitmind.exam.domain.dto.ActionSubmitDTO;
import com.fitmind.exam.domain.vo.ActionAssessmentVO;
import com.fitmind.exam.service.IActionAssessmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 动作考核（学员端提交）。
 */
@Validated
@RestController
@RequestMapping("/action-assessments")
@RequiredArgsConstructor
@Tag(name = "动作考核（学员端）")
public class ActionAssessmentController {

    private final IActionAssessmentService actionAssessmentService;

    @Operation(summary = "提交动作考核视频")
    @PostMapping
    public Long submit(@RequestBody @Valid ActionSubmitDTO dto) {
        return actionAssessmentService.submit(dto);
    }

    @Operation(summary = "我的提交记录")
    @GetMapping("/page")
    public PageDTO<ActionAssessmentVO> mySubmissions(PageQuery query) {
        return actionAssessmentService.mySubmissions(query);
    }

    @Operation(summary = "考核详情（含教练评分与评语）")
    @Parameters(@Parameter(name = "id", description = "考核记录id"))
    @GetMapping("/{id}")
    public ActionAssessmentVO detail(@PathVariable("id") Long id) {
        return actionAssessmentService.detail(id);
    }
}
