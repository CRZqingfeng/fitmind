package com.fitmind.api.client.pay;

import com.fitmind.api.dto.pay.ApplyPayDTO;
import com.fitmind.api.client.pay.fallback.PayClientFallback;
import com.fitmind.api.dto.pay.PayResultDTO;
import com.fitmind.api.dto.pay.RefundApplyDTO;
import com.fitmind.api.dto.pay.RefundResultDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 支付服务 Feign 客户端。
 *
 * <p>交易模块下单后用它申请支付。注意<b>金额由交易侧给出</b>
 * （从 {@code trade_order.real_amount} 取），支付侧只负责收钱 ——
 * 这样"这门课卖多少钱"只有一个数据源。</p>
 *
 * <p>M4-4 增加了退款两个方法，同样是<b>内部接口</b>：退款金额由交易侧
 * 从订单明细取，用户端只能提交"我要退哪个明细"。</p>
 */
@FeignClient(value = "fm-pay", fallbackFactory = PayClientFallback.class)
public interface PayClient {

    @PostMapping("/internal/pay-orders/apply")
    PayResultDTO applyPay(@RequestBody ApplyPayDTO dto);

    /**
     * 申请退款。
     *
     * <p>⚠️ 返回值里的 {@code status} <b>可能仍是"退款中"</b>（渠道异步），
     * 最终结果由 MQ {@code refund.result} 异步回传 —— 交易侧不要在这里等结论。</p>
     */
    @PostMapping("/internal/refund-orders/apply")
    RefundResultDTO applyRefund(@RequestBody RefundApplyDTO dto);

    /** 查询退款结果（对账 / 兜底查询用） */
    @GetMapping("/internal/refund-orders/{bizRefundOrderNo}")
    RefundResultDTO queryRefund(@PathVariable("bizRefundOrderNo") String bizRefundOrderNo);
}
