package com.fitmind.exam.controller;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.domain.query.PageQuery;
import com.fitmind.exam.domain.dto.ActionReviewDTO;
import com.fitmind.exam.domain.vo.ActionAssessmentVO;
import com.fitmind.exam.service.IActionAssessmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 动作考核管理端（教练评分）。
 *
 * <p><b>★ 权限边界</b>：路径在 {@code /admin/**} 下，资源服务会要求登录；
 * 而"是不是教练"由 Service 层判断（{@code UserClient#queryUserType}）——
 * 因为评分这件事<b>教练和管理员都该能做</b>，
 * 不能套用 fm-learning 那个"只允许管理员"的拦截器。</p>
 */
@Validated
@RestController
@RequestMapping("/admin/action-assessments")
@RequiredArgsConstructor
@Tag(name = "动作考核（管理端 / 教练评分）")
public class ActionAssessmentAdminController {

    private final IActionAssessmentService actionAssessmentService;

    @Operation(summary = "给动作考核评分（并发下只有一次生效）")
    @PutMapping("/review")
    public void review(@RequestBody @Valid ActionReviewDTO dto) {
        actionAssessmentService.review(dto);
    }

    @Operation(summary = "待评分列表")
    @GetMapping("/pending")
    public PageDTO<ActionAssessmentVO> pending(PageQuery query) {
        return actionAssessmentService.pendingList(query);
    }
}
