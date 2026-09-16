package com.fitmind.learning.mq;

import com.fitmind.common.constants.MqConstants;
import com.fitmind.common.mq.message.OrderPayMessage;
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
 * 订单已支付的消费者（fm-trade 生产）。
 *
 * <p><b>这条链路就是"付费课报名"</b>：用户付完钱 → 订单变已支付 →
 * 这里收到消息 → 把课程写进课表 → 学员可以开始练了。</p>
 *
 * <p><b>★ 为什么报名不能由用户直接调接口</b>：报名是<b>支付成功的后果</b>，
 * 触发权必须在交易侧。否则改一个请求就能把付费课加进自己的课表 ——
 * 常见实现里 {@code addUserLessons} 正是由订单消息驱动的。</p>
 *
 * <p><b>幂等</b>：由 {@code learning_lesson} 的 {@code (user_id, course_id)}
 * 唯一索引保证（M3-1 就建好了）。MQ 重复投递时插入冲突，捕获异常即可。
 * 服务内部还查了一次"是否已有未过期的课表"，属于提前返回、不白写。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPayListener {

    private final ILearningLessonService lessonService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.ORDER_PAY_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.ORDER_PAY_KEY
    ))
    public void listenOrderPay(OrderPayMessage message) {
        if (message == null || message.getUserId() == null || message.getCourseIds() == null) {
            log.warn("收到无效的订单支付消息：{}", message);
            return;
        }
        int n = lessonService.addUserLessons(message.getUserId(), message.getCourseIds());
        log.info("订单支付已处理：orderId={}, userId={}, 课程 {} 门（新建课表 {} 条）",
                message.getOrderId(), message.getUserId(), message.getCourseIds().size(), n);
    }
}
