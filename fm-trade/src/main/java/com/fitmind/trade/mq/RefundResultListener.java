package com.fitmind.trade.mq;

import com.fitmind.common.constants.MqConstants;
import com.fitmind.common.mq.message.RefundResultMessage;
import com.fitmind.trade.service.IRefundApplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 退款结果通知的消费者（fm-pay 生产）。
 *
 * <p><b>★ 为什么退款结果走 MQ 而不是同步调用</b>：真实渠道的退款是<b>异步</b>的。
 * 调渠道的退款接口只得到"已受理"，最终成不成要等它回调（或者我们主动查）。
 * 也就是说"调用返回"和"结果确定"在时间上就是分离的，</p>
 * <p>所以交易侧不能依赖"调完就知道结果" —— 必须有一个地方接住<b>稍后到达</b>的结果。
 * MQ 天然就是这个角色：一次调用、两次通信（申请 / 结果）。</p>
 *
 * <p><b>幂等</b>不在消息本身，而在消费侧：{@code refund_apply} 的状态条件更新
 * （{@code WHERE status = 同意退款}）—— 重复投递时第二次更新 0 行，
 * 于是不会重复改订单、不会重复撤销报名、不会重复发通知。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundResultListener {

    private final IRefundApplyService refundApplyService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.REFUND_RESULT_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.PAY_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.REFUND_RESULT_KEY
    ))
    public void listenRefundResult(RefundResultMessage message) {
        if (message == null || message.getBizRefundOrderNo() == null) {
            log.warn("收到无效的退款结果消息：{}", message);
            return;
        }
        boolean handled = refundApplyService.handleRefundResult(message);
        if (!handled) {
            // 两种正常情况：① 退款仍在途（"退款中"） ② 已经处理过（重复投递）
            log.info("退款结果消息未产生状态变更（在途或已处理）：bizRefundOrderNo={}, status={}",
                    message.getBizRefundOrderNo(), message.getStatus());
        }
    }
}
