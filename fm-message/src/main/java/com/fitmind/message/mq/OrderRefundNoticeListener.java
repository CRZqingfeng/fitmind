package com.fitmind.message.mq;

import com.fitmind.common.constants.MqConstants;
import com.fitmind.common.mq.message.OrderRefundMessage;
import com.fitmind.message.constants.MessageConstants;
import com.fitmind.message.service.INoticeService;
import com.fitmind.message.util.AmountUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 订单退款到账 → 发"退款到账"通知（fm-trade 生产）。
 *
 * <p>这是 M4-4 里"通知"和"交易"真正接上的一环：用户申请退款 → 审批 → 渠道退款成功
 * → 交易侧发 {@code order.refund} 事件 → <b>这里发站内信（并可选发短信）</b>。</p>
 *
 * <p><b>★ 退款通知是"必须发"的通知</b>：钱的事用户最在意。
 * 没有这条通知，用户只能自己不停去查"我的退款到哪了" —— 这是客服量的主要来源之一。</p>
 *
 * <p><b>幂等</b>：{@code sourceId = refundApplyId}（退款申请id）。
 * MQ 重投或者交易侧因重试重复发事件，都不会让用户收到两条"退款到账"。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderRefundNoticeListener {

    private final INoticeService noticeService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.REFUND_NOTICE_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.ORDER_REFUND_KEY
    ))
    public void listenOrderRefund(OrderRefundMessage message) {
        if (message == null || message.getUserId() == null || message.getRefundApplyId() == null) {
            log.warn("收到无效的订单退款消息：{}", message);
            return;
        }
        // ★ 用 HashMap 而不是 Map.of：Map.of 不允许 null 值，
        //   而课程名在极端情况下可能为空（课程快照缺失），那样会抛 NPE
        Map<String, Object> params = new HashMap<>(4);
        params.put("courseName", message.getCourseName() == null ? "你所购买的课程" : message.getCourseName());
        params.put("amount", AmountUtil.fenToYuan(message.getRefundAmount()));
        // ★ 幂等键用 refundApplyId：同一笔退款申请只会产生一条到账通知
        noticeService.sendByTemplate(MessageConstants.TPL_REFUND_SUCCESS,
                message.getUserId(), params, message.getRefundApplyId());
        log.info("退款到账通知已处理：applyId={}, userId={}, 金额={}分",
                message.getRefundApplyId(), message.getUserId(), message.getRefundAmount());
    }
}
