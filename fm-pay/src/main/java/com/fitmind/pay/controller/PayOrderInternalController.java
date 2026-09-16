package com.fitmind.pay.controller;

import com.fitmind.pay.domain.dto.ApplyPayDTO;
import com.fitmind.pay.domain.vo.PayResultVO;
import com.fitmind.pay.service.IPayOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付内部接口（供 fm-trade 的 Feign 调用，不对用户开放）。
 *
 * <p><b>★ 为什么申请支付必须是内部接口</b>：金额要从<b>服务端的订单</b>取。
 * 如果开放给用户调，就意味着"我说多少钱就多少钱" ——
 * 改个请求体就能用 1 分钱买下 199 的课。</p>
 *
 * <p>网关侧 {@code /ps/**} 需要登录，所以外部用户直接调这个路径会拿到 401；
 * 但它真正的安全边界在于<b>只有 fm-trade 会构造这个请求</b>。</p>
 */
@Validated
@RestController
@RequestMapping("/internal/pay-orders")
@RequiredArgsConstructor
@Tag(name = "支付内部接口（供交易服务调用）")
public class PayOrderInternalController {

    private final IPayOrderService payOrderService;

    @Operation(summary = "申请支付（创建支付单并返回支付链接）")
    @PostMapping("/apply")
    public PayResultVO apply(@RequestBody @Valid ApplyPayDTO dto) {
        return payOrderService.applyPay(dto);
    }
}
