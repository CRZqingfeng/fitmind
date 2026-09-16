package com.fitmind.api.client.pay.fallback;

import com.fitmind.api.client.pay.PayClient;
import com.fitmind.api.dto.pay.ApplyPayDTO;
import com.fitmind.api.dto.pay.PayResultDTO;
import com.fitmind.api.dto.pay.RefundApplyDTO;
import com.fitmind.api.dto.pay.RefundResultDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

/**
 * {@link PayClient} 的降级工厂。
 *
 * <p>支付服务不可用时返回 <b>null</b> 而不是一个假的 DTO ——
 * 调用方（下单 / 退款）必须能区分"调用失败"和"调用成功"：
 * 返回假对象会让订单看起来已经能付了，用户扫一个不存在的码；
 * 在退款场景里更危险 —— 假对象会让交易侧以为"退款已受理"，
 * 而这笔钱其实<b>根本没往渠道发</b>，用户永远等不到退款。</p>
 *
 * <p><b>★ 返回 null 之后调用方该怎么做（很重要）</b>：退款申请<b>不能</b>
 * 因此被标成"失败"。因为"支付服务不可用"与"渠道拒绝退款"是两件事 ——
 * 前者是暂时性的、钱一分没动，后者是确定性的失败。
 * 正确做法是让退款申请<b>停在"已同意"</b>，由重试或人工介入推进。
 * 把它标失败会让用户看到"退款失败"，而实际上钱还在我们账上。</p>
 */
@Slf4j
@Component
@ConditionalOnClass(FallbackFactory.class)
public class PayClientFallback implements FallbackFactory<PayClient> {

    @Override
    public PayClient create(Throwable cause) {
        return new PayClient() {
            @Override
            public PayResultDTO applyPay(ApplyPayDTO dto) {
                log.error("支付服务不可用，申请支付失败。bizOrderNo={}，原因：{}",
                        dto == null ? null : dto.getBizOrderNo(), cause.getMessage());
                return null;
            }

            @Override
            public RefundResultDTO applyRefund(RefundApplyDTO dto) {
                log.error("支付服务不可用，申请退款失败。bizRefundOrderNo={}，原因：{}",
                        dto == null ? null : dto.getBizRefundOrderNo(), cause.getMessage());
                // ★ 返回 null 表示"没打通"，调用方应保持"已同意"待重试，而不是标记退款失败
                return null;
            }

            @Override
            public RefundResultDTO queryRefund(String bizRefundOrderNo) {
                log.error("支付服务不可用，查询退款结果失败。bizRefundOrderNo={}，原因：{}",
                        bizRefundOrderNo, cause.getMessage());
                return null;
            }
        };
    }
}
