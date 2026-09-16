package com.fitmind.trade.controller;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.trade.domain.dto.RefundCancelDTO;
import com.fitmind.trade.domain.dto.RefundFormDTO;
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
 * 退款申请接口（学员端）。
 *
 * <p><b>★ 用户端只能提交"我要退哪门课"，提交不了金额</b>（{@link RefundFormDTO} 里没有金额字段）——
 * 金额由服务端从订单明细的实付金额取。这是 M4-2"申请支付金额由服务端给出"的同一原则：
 * 钱的事只有一个数据源，否则改个请求体就能退 1 万块。</p>
 *
 * <p>审批类接口在 {@code /admin/refund-apply/**}（见 {@code RefundApplyAdminController}），
 * 由 {@code AdminAuthInterceptor} 强制校验管理员身份。</p>
 */
@Validated
@RestController
@RequestMapping("/refund-apply")
@RequiredArgsConstructor
@Tag(name = "退款申请（学员端）")
public class RefundApplyController {

    private final IRefundApplyService refundApplyService;

    @Operation(summary = "提交退款申请")
    @PostMapping
    public Long applyRefund(@RequestBody @Valid RefundFormDTO dto) {
        return refundApplyService.applyRefund(dto);
    }

    @Operation(summary = "撤销退款申请（只能撤待审批的）")
    @PutMapping("/cancel")
    public void cancelRefund(@RequestBody RefundCancelDTO dto) {
        refundApplyService.cancel(dto);
    }

    @Operation(summary = "我的退款申请分页")
    @GetMapping("/page")
    public PageDTO<RefundApplyPageVO> myRefunds(RefundApplyPageQuery query) {
        return refundApplyService.queryMyRefunds(query);
    }

    @Operation(summary = "按订单明细查最近一次退款（我的课程页用）")
    @Parameters(@Parameter(name = "detailId", description = "订单明细id"))
    @GetMapping("/detail/{detailId}")
    public RefundApplyVO detailByDetailId(@PathVariable("detailId") Long detailId) {
        return refundApplyService.queryDetailByDetailId(detailId);
    }

    @Operation(summary = "退款详情")
    @Parameters(@Parameter(name = "id", description = "退款申请id"))
    @GetMapping("/{id}")
    public RefundApplyVO detail(@PathVariable("id") Long id) {
        return refundApplyService.queryDetail(id);
    }
}
