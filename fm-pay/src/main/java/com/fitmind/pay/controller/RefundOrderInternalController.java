package com.fitmind.pay.controller;

import com.fitmind.pay.domain.dto.RefundApplyDTO;
import com.fitmind.pay.domain.vo.RefundResultVO;
import com.fitmind.pay.service.IRefundOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 退款内部接口（供 fm-trade 的 Feign 调用，不对用户开放）。
 *
 * <p><b>★ 为什么退款也必须是内部接口</b>：与申请支付同理 ——
 * <b>退款金额由交易侧从订单明细取</b>，不能让前端传。
 * 如果开放给用户，改个请求体就能申请退 1 万（虽然还有金额上限校验兜着，
 * 但"能自己指定退多少"本身就是不该出现的接口设计）。</p>
 *
 * <p>用户侧的入口是 {@code POST /ts/refund-apply}（在 fm-trade 里），
 * 它只接受"我要退哪个订单明细"，金额由服务端自己算。</p>
 */
@Validated
@RestController
@RequestMapping("/internal/refund-orders")
@RequiredArgsConstructor
@Tag(name = "退款内部接口（供交易服务调用）")
public class RefundOrderInternalController {

    private final IRefundOrderService refundOrderService;

    @Operation(summary = "申请退款")
    @PostMapping("/apply")
    public RefundResultVO apply(@RequestBody @Valid RefundApplyDTO dto) {
        return refundOrderService.applyRefund(dto);
    }

    @Operation(summary = "查询退款结果（对账 / 兜底查询）")
    @Parameters(@Parameter(name = "bizRefundOrderNo", description = "业务退款单号（refund_apply.id）"))
    @GetMapping("/{bizRefundOrderNo}")
    public RefundResultVO query(@PathVariable("bizRefundOrderNo") String bizRefundOrderNo) {
        return refundOrderService.queryByBizRefundOrderNo(bizRefundOrderNo);
    }
}
