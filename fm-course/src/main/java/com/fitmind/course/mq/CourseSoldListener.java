package com.fitmind.course.mq;

import com.fitmind.common.constants.MqConstants;
import com.fitmind.common.mq.message.OrderPayMessage;
import com.fitmind.common.mq.message.OrderRefundMessage;
import com.fitmind.common.utils.RabbitMqHelper;
import com.fitmind.course.mapper.CourseMapper;
import com.fitmind.course.mapper.CourseSoldLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 报名人数（{@code course.sold}）维护监听器（M7-3）。
 *
 * <h3>为什么在这里维护</h3>
 * <p>前端课程列表要展示"多少人练过"，ES 里也一直有这个字段（还支持按它排序），
 * 但它的值一直是硬编码的 0 —— 因为课程表根本没有这一列。
 * 而"报名"这件事的真相分散在两处：已支付订单在 fm-trade、课表记录在 fm-learning，
 * 都在别的库。让 fm-search 为了一个数字去调 fm-learning，等于把搜索服务
 * 绑到学习服务上（搜索本该只依赖课程数据）。</p>
 *
 * <p>所以这里加一列冗余计数，由业务事件驱动维护。这也正是
 * <b>topic 交换机"一条事件、多个消费者"</b>的用法：fm-trade 发
 * {@code order.pay}，fm-learning 写课表、fm-message 发通知、fm-data 统计成交，
 * 现在再加上 fm-course 数人头 —— <b>生产方一行都不用改</b>。</p>
 *
 * <h3>★ 语义边界（诚实记录）</h3>
 * <p>本实现消费的是"订单支付成功"，不是"报名成功"。两者在本项目里几乎等价
 * （支付成功即触发报名），但如果将来 fm-learning 的报名会失败
 * （比如课程刚好过期），这里就已经先加过头了。
 * 更严格的做法是由 fm-learning 在报名成功后发一条"报名成功"事件，
 * fm-course 消费它 —— 那是"因果驱动"而不是"同一触发条件的并列消费者"。
 * 当前不改 fm-learning，是因为这一列只用于展示与排序，
 * 不值得为一个展示字段去动交易/学习链路。</p>
 *
 * <h3>★ 为什么 catch 住而不让它重投</h3>
 * <p>{@code order.pay} 的另外三个消费者里，fm-trade 的
 * {@code PaySuccessListener} 是<b>不 catch</b> 的（订单状态是关键数据，重投才有价值）；
 * 这里跟 fm-search 的 {@code CourseEventListener} 一致，选择 catch + error 日志 ——
 * 本项目没有死信队列，抛异常等于无限重投刷屏，而这一列只是展示用统计值。
 * 代价是"更新失败就少算一次"，补救手段是重放消息（幂等表保证不会重复计数）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseSoldListener {

    /** 报名：+1 */
    private static final int ENROLL = 1;

    /** 退款：-1（与 fm-learning 收到 order.refund 后撤销课表保持一致） */
    private static final int REFUND = -1;

    private final CourseMapper courseMapper;
    private final CourseSoldLogMapper soldLogMapper;
    private final RabbitMqHelper rabbitMqHelper;

    /**
     * 订单支付成功 → 报名人数 +1。
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.COURSE_SOLD_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.ORDER_PAY_KEY
    ))
    @Transactional(rollbackFor = Exception.class)
    public void listenOrderPay(OrderPayMessage message) {
        if (message == null || message.getOrderId() == null || message.getCourseIds() == null) {
            log.warn("收到无效的订单支付消息，已忽略：{}", message);
            return;
        }
        try {
            apply(message.getOrderId(), message.getCourseIds(), ENROLL, "报名");
        } catch (Exception e) {
            log.error("更新报名人数失败（该统计值需要重放消息修复）：orderId={}, courseIds={}，原因：{}",
                    message.getOrderId(), message.getCourseIds(), e.getMessage(), e);
        }
    }

    /**
     * 订单退款成功 → 报名人数 -1。
     *
     * <p>为什么退款要减：fm-learning 收到同一条消息后会<b>撤销课表</b>
     * （钱退了，权益不该留）。如果 sold 只加不减，就会出现
     * "显示 10 人在练、实际只有 9 个人有课"。</p>
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.COURSE_SOLD_REFUND_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.ORDER_REFUND_KEY
    ))
    @Transactional(rollbackFor = Exception.class)
    public void listenOrderRefund(OrderRefundMessage message) {
        if (message == null || message.getOrderId() == null || message.getCourseIds() == null) {
            log.warn("收到无效的订单退款消息，已忽略：{}", message);
            return;
        }
        try {
            apply(message.getOrderId(), message.getCourseIds(), REFUND, "退款");
        } catch (Exception e) {
            log.error("回退报名人数失败（该统计值需要重放消息修复）：orderId={}, courseIds={}，原因：{}",
                    message.getOrderId(), message.getCourseIds(), e.getMessage(), e);
        }
    }

    /**
     * 对一批课程做同一方向的增量更新，并让 fm-search 刷新这些课程的索引。
     *
     * <p>注意<b>没有</b>用 {@code courseService.updateById(...)} ——
     * 那样是"读出来改一改写回去"，并发下会丢更新（见 {@code CourseMapper#increaseSold} 的注释）。</p>
     */
    private void apply(Long orderId, List<Long> courseIds, int delta, String action) {
        List<Long> changed = new ArrayList<>();
        int skipped = 0;

        for (Long courseId : courseIds) {
            if (courseId == null) {
                continue;
            }
            // ① 先登记：返回 0 说明这条消息（这一门课、这个方向）之前已经处理过
            if (soldLogMapper.insertIfAbsent(orderId, courseId, delta) == 0) {
                skipped++;
                continue;
            }
            // ② 再更新计数：SQL 是原子的 sold = sold + delta，不需要额外加锁
            int rows = courseMapper.increaseSold(courseId, delta);
            if (rows > 0) {
                changed.add(courseId);
            } else {
                // 课程不存在 / 已被删除：登记已经写进去了，所以不会反复重算
                log.warn("课程不存在或已删除，报名人数未变动：courseId={}", courseId);
            }
        }

        // ③ 只刷新真正变过的课程，避免把没动过的文档也重写一遍
        for (Long courseId : changed) {
            rabbitMqHelper.sendAfterCommit(MqConstants.Exchange.COURSE_EXCHANGE,
                    MqConstants.Key.COURSE_UPDATE_KEY, courseId);
        }

        log.info("报名人数已{}：orderId={}, 变动 {} 门课程, 幂等跳过 {} 门{}",
                action, orderId, changed.size(), skipped,
                changed.isEmpty() ? "" : "，课程：" + changed);
    }
}
