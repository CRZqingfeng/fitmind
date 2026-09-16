package com.fitmind.message.mq;

import com.fitmind.api.client.course.CourseClient;
import com.fitmind.api.dto.course.CourseSimpleDTO;
import com.fitmind.common.constants.MqConstants;
import com.fitmind.common.mq.message.OrderPayMessage;
import com.fitmind.message.constants.MessageConstants;
import com.fitmind.message.service.INoticeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 订单已支付 → 发"购课成功"通知（fm-trade 生产）。
 *
 * <p><b>★ 为什么是 MQ 而不是让交易侧同步调消息服务</b>：</p>
 * <ol>
 *   <li><b>不阻塞主链路</b>：支付回调处理里多一次 HTTP 调用，就多一个
 *       让"用户付了钱但订单没更新"的理由</li>
 *   <li><b>可重放</b>：消息在队列里，消息服务重启也不会丢</li>
 *   <li><b>可扩展</b>：将来要"发通知 + 记统计 + 给 AI 教练发信号"，
 *       各自订阅即可，<b>交易侧一行都不用改</b></li>
 * </ol>
 *
 * <p><b>★ 这条 routing key（{@code order.pay}）被两个队列绑定</b>：
 * {@code order.pay.queue}（fm-learning 写课表）和本类监听的
 * {@code order.pay.notice.queue}（发通知）。两者互不影响 ——
 * 写课表失败不会导致通知发不出去，反之亦然。</p>
 *
 * <p><b>幂等</b>：{@code sendByTemplate} 里用 {@code sourceId = orderId} 做唯一键，
 * MQ 重投时第二次插入冲突、直接忽略。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPayNoticeListener {

    private final INoticeService noticeService;
    private final CourseClient courseClient;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.ORDER_PAY_NOTICE_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.ORDER_PAY_KEY
    ))
    public void listenOrderPay(OrderPayMessage message) {
        if (message == null || message.getUserId() == null || message.getCourseIds() == null) {
            log.warn("收到无效的订单支付消息：{}", message);
            return;
        }
        String courseName = describeCourses(message.getCourseIds());
        // ★ M5 补的金额：消息体加了 amount，通知文案就能显示"实付多少"。
        //   没有金额的"购买成功"通知，用户其实没法确认自己付了多少 —— 这是笔小改进
        String amount = message.getAmount() == null
                ? "0" : String.format("%.2f", message.getAmount() / 100.0);
        // ★ 幂等键用 orderId：同一笔订单只会产生一条"购课成功"通知
        noticeService.sendByTemplate(MessageConstants.TPL_PURCHASE_SUCCESS,
                message.getUserId(),
                Map.of("courseName", courseName, "amount", amount),
                message.getOrderId());
        log.info("购课成功通知已处理：orderId={}, userId={}, 课程={}",
                message.getOrderId(), message.getUserId(), courseName);
    }

    /**
     * 把课程列表描述成一句人话。
     *
     * <p>单门课用真名（体验好），多门课用数量（否则文案会很长）。
     * 课程名从课程服务查 —— 查不到就降级成"训练课程"，
     * <b>不因为拿不到课程名就不发通知</b>：用户已经付了钱，
     * "买成功了"这个信息比"买的是哪门课"更重要。</p>
     */
    private String describeCourses(List<Long> courseIds) {
        if (courseIds.size() > 1) {
            return courseIds.size() + " 门训练课程";
        }
        try {
            List<CourseSimpleDTO> list = courseClient.queryCourseSimpleList(courseIds);
            if (list == null || list.isEmpty() || list.get(0).getName() == null) {
                return "训练课程";
            }
            return "《" + list.get(0).getName() + "》";
        } catch (Exception e) {
            // 课程服务不可用：降级成通用文案，不影响通知发出
            log.warn("查询课程名失败，使用通用文案：courseIds={}", courseIds, e);
            return "训练课程";
        }
    }

    /** 保留给将来可能的"多课分别通知"场景 */
    @SuppressWarnings("unused")
    private List<Long> safe(List<Long> ids) {
        return ids == null ? Collections.emptyList() : ids;
    }
}
