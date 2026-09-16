package com.fitmind.learning.mq;

import com.fitmind.common.constants.MqConstants;
import com.fitmind.common.mq.message.OrderRefundMessage;
import com.fitmind.learning.service.ILearningLessonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 订单退款到账的消费者（fm-trade 生产）。
 *
 * <p><b>这条链路是"退款后撤销权益"</b>：钱退给用户了 → 交易侧发事件 →
 * 这里把课程从课表里移除。与 {@code OrderPayListener}（付款写课表）互为逆操作。</p>
 *
 * <p><b>★ 为什么撤销报名也不能由用户直接调接口</b>：与"报名"同理 ——
 * 撤销权益是<b>退款成功的后果</b>，触发权必须在交易侧。否则用户可以在没退款的情况下
 * 自己把课退掉（虽然只是丢权益，但订单和课表就对不上了）。
 * 反过来，如果由用户调，那"退款是否真的到账"这个前提就没人保证了。</p>
 *
 * <p><b>幂等</b>：{@code removeUserLessons} 里"课表不存在就直接跳过"，
 * MQ 重复投递时第二次什么都没做。这正是 at-least-once 消息的正确处理方式 ——
 * <b>让操作本身幂等，而不是靠消息去重表</b>。</p>
 *
 * <p><b>★ 注意这条 routing key 有两个消费者</b>（本类和 fm-message 的通知消费者），
 * 绑的是不同队列。所以"撤销课表"和"发通知"互不阻塞 ——
 * 通知服务挂了也不会影响用户的学习权益被正确收回。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderRefundListener {

    private final ILearningLessonService lessonService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.ORDER_REFUND_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.ORDER_REFUND_KEY
    ))
    public void listenOrderRefund(OrderRefundMessage message) {
        if (message == null || message.getUserId() == null || message.getCourseIds() == null) {
            log.warn("收到无效的订单退款消息：{}", message);
            return;
        }
        int removed = lessonService.removeUserLessons(message.getUserId(), message.getCourseIds());
        log.info("订单退款已处理：orderId={}, userId={}, 课程 {} 门（撤销课表 {} 条）",
                message.getOrderId(), message.getUserId(), message.getCourseIds().size(), removed);
    }
}
