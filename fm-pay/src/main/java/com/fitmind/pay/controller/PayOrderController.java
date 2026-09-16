package com.fitmind.pay.controller;

import com.fitmind.common.utils.UserContext;
import com.fitmind.pay.domain.po.PayOrder;
import com.fitmind.pay.service.IPayOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * 支付单查询接口。
 *
 * <p>申请支付不走这里 —— 那是 fm-trade 通过 Feign 调的内部接口
 * （见 {@code PayOrderInternalController}），因为<b>金额必须由交易侧给出</b>，
 * 不能让前端传。</p>
 */
@Validated
@RestController
@RequestMapping("/pay-orders")
@RequiredArgsConstructor
@Tag(name = "支付单接口")
public class PayOrderController {

    private final IPayOrderService payOrderService;

    @Operation(summary = "查询支付单状态（前端轮询支付结果）")
    @Parameters(@Parameter(name = "payOrderNo", description = "支付单号"))
    @GetMapping("/{payOrderNo}")
    public PayOrder detail(@PathVariable("payOrderNo") String payOrderNo) {
        PayOrder po = payOrderService.getByPayOrderNo(payOrderNo);
        if (po == null) {
            return null;
        }
        // 只能查自己的
        if (!Objects.equals(po.getBizUserId(), UserContext.getUser())) {
            return null;
        }
        return po;
    }
}
