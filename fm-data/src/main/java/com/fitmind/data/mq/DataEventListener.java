package com.fitmind.data.mq;

import com.fitmind.common.constants.MqConstants;
import com.fitmind.common.mq.message.AssessmentCreatedMessage;
import com.fitmind.common.mq.message.OrderPayMessage;
import com.fitmind.common.mq.message.OrderRefundMessage;
import com.fitmind.common.mq.message.PointsEventMessage;
import com.fitmind.common.mq.message.SectionLearnedMessage;
import com.fitmind.common.mq.message.SignInMessage;
import com.fitmind.data.constants.DataEventType;
import com.fitmind.data.constants.DataMetric;
import com.fitmind.data.service.IDataCollectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 数据中心事件监听。
 *
 * <p><b>本模块的特征：只有消费者，没有生产者。</b>
 * 六个监听方法分别订阅别人已经发出的业务事件 ——
 * 业务服务（fm-learning / fm-course / fm-trade / fm-exam）
 * <b>完全不知道数据中心的存在</b>。数据中心挂了，用户照样下单学习。</p>
 *
 * <p>★ 每个 bizId 怎么选，决定了"什么算重复"：</p>
 * <ul>
 *   <li>签到：{@code userId:yyyyMMdd} —— 一人一天只算一次</li>
 *   <li>完成训练：{@code userId:lessonId:sectionId} —— 同一节重复练只算一次</li>
 *   <li>小测：{@code userId:bizId} —— 与积分侧同一口径（按题去重）</li>
 *   <li>成交 / 退款：订单 id / 退款申请 id —— 本身就是全局唯一</li>
 *   <li>体测：体测记录 id</li>
 * </ul>
 *
 * <p><b>消费失败怎么办</b>：只记日志不抛异常。理由和 M2-3、M3-2 一致 ——
 * 当前没有死信队列，抛异常会导致无限重投刷屏。
 * 但<b>必须记 error 而不是 warn</b>：漏统计是要能被发现的。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataEventListener {

    private final IDataCollectService collectService;

    // ==================== 签到 ====================
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.DATA_SIGN_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.SIGN_IN
    ))
    public void listenSignIn(SignInMessage message) {
        if (message == null || message.getUserId() == null) {
            log.warn("签到消息体有误，忽略：{}", message);
            return;
        }
        // message.getBizId() 就是 yyyyMMdd（签到侧拼好的）
        LocalDate date = parseDay(message.getBizId());
        safe(() -> collectService.collect(DataEventType.SIGN_IN,
                message.getUserId() + ":" + message.getBizId(),
                message.getUserId(), date,
                IDataCollectService.MetricDelta.one(DataMetric.SIGN_IN_COUNT)));
    }

    // ==================== 完成训练 ====================
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.DATA_LEARN_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.SECTION_LEARNED
    ))
    public void listenSectionLearned(SectionLearnedMessage message) {
        if (message == null || message.getUserId() == null) {
            log.warn("完成训练消息体有误，忽略：{}", message);
            return;
        }
        safe(() -> collectService.collect(DataEventType.SECTION_LEARNED,
                message.getUserId() + ":" + message.bizId(),
                message.getUserId(), LocalDate.now(),
                IDataCollectService.MetricDelta.one(DataMetric.LEARN_COUNT)));
    }

    // ==================== 小测答对 ====================
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.DATA_PRACTICE_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.PRACTICE_ANSWERED
    ))
    public void listenPracticeAnswered(PointsEventMessage message) {
        if (message == null || message.getUserId() == null) {
            log.warn("小测消息体有误，忽略：{}", message);
            return;
        }
        safe(() -> collectService.collect(DataEventType.PRACTICE_ANSWERED,
                message.getUserId() + ":" + message.getBizId(),
                message.getUserId(), LocalDate.now(),
                IDataCollectService.MetricDelta.one(DataMetric.PRACTICE_COUNT)));
    }

    // ==================== 成交 ====================
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.DATA_ORDER_PAY_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.ORDER_PAY_KEY
    ))
    public void listenOrderPay(OrderPayMessage message) {
        if (message == null || message.getOrderId() == null) {
            log.warn("订单支付消息体有误，忽略：{}", message);
            return;
        }
        long amount = message.getAmount() == null ? 0L : message.getAmount();
        // 全局：一单记一次（订单数 +1，成交额 +全额）
        safe(() -> collectService.collect(DataEventType.ORDER_PAY,
                String.valueOf(message.getOrderId()),
                message.getUserId(), LocalDate.now(),
                IDataCollectService.MetricDelta.one(DataMetric.ORDER_COUNT),
                IDataCollectService.MetricDelta.of(DataMetric.ORDER_AMOUNT, amount)));

        // 课程维度：每门课各 +1 销量，金额<b>按课程数均摊</b>。
        //
        // ★ 为什么均摊而不是每门课都记全额：那样 3 门课的销售额会变成 GMV 的 3 倍，
        //   报表上"课程销售额合计"对不上"总成交额"，一看就是错的。
        // ★ 为什么不用明细的实际价格：这条消息只带 courseIds 不带各门课的单价
        //   （见 MqConstants 的设计——它是给"写课表"和"发通知"用的）。
        //   要精确就得改消息体加明细金额，那是 M4 的契约；
        //   对"销量榜"这个用途来说，均摊已经够用，且合计自洽。
        if (message.getCourseIds() != null && !message.getCourseIds().isEmpty()) {
            int n = message.getCourseIds().size();
            long per = amount / n;
            long remainder = amount - per * n;   // 除不尽的零头补给第一门课，保证合计分毫不差
            final long head = per + remainder;
            boolean first = true;
            for (Long courseId : message.getCourseIds()) {
                final long amt = first ? head : per;
                first = false;
                safe(() -> collectService.collectCourse(courseId, LocalDate.now(), 1, amt, 0));
            }
        }
    }

    // ==================== 退款 ====================
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.DATA_ORDER_REFUND_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.ORDER_REFUND_KEY
    ))
    public void listenOrderRefund(OrderRefundMessage message) {
        if (message == null || message.getRefundApplyId() == null) {
            log.warn("退款消息体有误，忽略：{}", message);
            return;
        }
        long amount = message.getRefundAmount() == null ? 0L : message.getRefundAmount();
        safe(() -> collectService.collect(DataEventType.ORDER_REFUND,
                String.valueOf(message.getRefundApplyId()),
                message.getUserId(), LocalDate.now(),
                IDataCollectService.MetricDelta.one(DataMetric.REFUND_COUNT),
                IDataCollectService.MetricDelta.of(DataMetric.REFUND_AMOUNT, amount)));

        if (message.getCourseIds() != null) {
            for (Long courseId : message.getCourseIds()) {
                safe(() -> collectService.collectCourse(courseId, LocalDate.now(), 0, 0, 1));
            }
        }
    }

    // ==================== 体测 ====================
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.DATA_ASSESSMENT_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.EXAM_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.ASSESSMENT_CREATED_KEY
    ))
    public void listenAssessment(AssessmentCreatedMessage message) {
        if (message == null || message.getAssessmentId() == null) {
            log.warn("体测消息体有误，忽略：{}", message);
            return;
        }
        // ★ 用体测发生时间而不是消息到达时间：支持补录
        LocalDate date = message.getAssessTime() == null
                ? LocalDate.now()
                : message.getAssessTime().toLocalDate();
        safe(() -> collectService.collect(DataEventType.ASSESSMENT,
                String.valueOf(message.getAssessmentId()),
                message.getUserId(), date,
                IDataCollectService.MetricDelta.one(DataMetric.ASSESS_COUNT)));
    }

    // ==================== 工具 ====================
    private void safe(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            // 只记日志不抛异常：没有死信队列时抛异常会无限重投。
            // 但记 error —— 漏统计必须能被发现。
            log.error("数据采集失败（本次统计丢失）：{}", e.getMessage(), e);
        }
    }

    /** yyyyMMdd → LocalDate；解析不了就退回今天（宁可归错一天，也不能丢掉这条统计） */
    private LocalDate parseDay(String day) {
        if (day == null || day.length() != 8) {
            return LocalDate.now();
        }
        try {
            return LocalDate.of(Integer.parseInt(day.substring(0, 4)),
                    Integer.parseInt(day.substring(4, 6)),
                    Integer.parseInt(day.substring(6, 8)));
        } catch (Exception e) {
            return LocalDate.now();
        }
    }
}
