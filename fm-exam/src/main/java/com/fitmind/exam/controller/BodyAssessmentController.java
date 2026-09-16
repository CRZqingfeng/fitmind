package com.fitmind.exam.controller;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.domain.query.PageQuery;
import com.fitmind.exam.domain.dto.BodyAssessFormDTO;
import com.fitmind.exam.domain.vo.BodyAssessVO;
import com.fitmind.exam.domain.vo.BodyTrendVO;
import com.fitmind.exam.service.IBodyAssessmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 体测评估（学员端）。
 *
 * <p>所有接口都只操作<b>当前登录用户</b>的数据 —— 收件人不从前端取。</p>
 */
@Validated
@RestController
@RequestMapping("/assessments")
@RequiredArgsConstructor
@Tag(name = "体测评估（学员端）")
public class BodyAssessmentController {

    private final IBodyAssessmentService bodyAssessmentService;

    @Operation(summary = "提交一次体测（BMI/体脂/腰臀比/基础代谢由服务端计算）")
    @PostMapping
    public Long assess(@RequestBody @Valid BodyAssessFormDTO dto) {
        return bodyAssessmentService.assess(dto);
    }

    @Operation(summary = "最新一次评估结论")
    @GetMapping("/latest")
    public BodyAssessVO latest() {
        return bodyAssessmentService.latest();
    }

    @Operation(summary = "★ 趋势：首次 vs 最新的变化量")
    @GetMapping("/trend")
    public BodyTrendVO trend() {
        return bodyAssessmentService.trend();
    }

    @Operation(summary = "我的体测历史")
    @GetMapping("/page")
    public PageDTO<BodyAssessVO> history(PageQuery query) {
        return bodyAssessmentService.myHistory(query);
    }

    @Operation(summary = "某次体测详情")
    @Parameters(@Parameter(name = "id", description = "体测记录id"))
    @GetMapping("/{id}")
    public BodyAssessVO detail(@PathVariable("id") Long id) {
        return bodyAssessmentService.detail(id);
    }
}
