package com.fitmind.trade.controller;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.trade.domain.dto.ApproveFormDTO;
import com.fitmind.trade.domain.query.RefundApplyPageQuery;
import com.fitmind.trade.domain.vo.RefundApplyPageVO;
import com.fitmind.trade.domain.vo.RefundApplyVO;
import com.fitmind.trade.service.IRefundApplyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 退款管理端接口。
 *
 * <p><b>★ 路径前缀 {@code /admin/} 不是命名习惯，而是权限边界</b>：
 * {@code AdminAuthInterceptor} 挂在这个前缀上，非管理员一律 403。
 * 把"只有管理员能做"的接口放进这个前缀，比在每个方法里写一句角色判断
 * 更难漏 —— 漏掉一个方法就是漏掉一个越权入口。</p>
 */
@Validated
@RestController
@RequestMapping("/admin/refund-apply")
@RequiredArgsConstructor
@Tag(name = "退款申请（管理端）")
public class RefundApplyAdminController {

    private final IRefundApplyService refundApplyService;

    @Operation(summary = "审批退款申请（同意 / 拒绝）")
    @PutMapping("/approval")
    public void approve(@RequestBody @Valid ApproveFormDTO dto) {
        refundApplyService.approve(dto);
    }

    @Operation(summary = "管理员直接退款（跳过审批）")
    @Parameters({@Parameter(name = "orderDetailId", description = "订单明细id"),
            @Parameter(name = "reason", description = "退款原因")})
    @PostMapping("/direct")
    public Long directRefund(@RequestParam("orderDetailId") Long orderDetailId,
                             @RequestParam(value = "reason", required = false) String reason) {
        return refundApplyService.adminRefund(orderDetailId, reason);
    }

    @Operation(summary = "退款申请分页（管理端）")
    @GetMapping("/page")
    public PageDTO<RefundApplyPageVO> page(RefundApplyPageQuery query) {
        return refundApplyService.queryAdminPage(query);
    }

    @Operation(summary = "下一个待审批的申请（逐条审批用）")
    @GetMapping("/next")
    public RefundApplyVO nextToApprove() {
        return refundApplyService.nextToApprove();
    }

    @Operation(summary = "退款详情（管理端）")
    @Parameters(@Parameter(name = "id", description = "退款申请id"))
    @GetMapping("/{id}")
    public RefundApplyVO detail(@PathVariable("id") Long id) {
        return refundApplyService.queryDetail(id);
    }
}
