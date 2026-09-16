package com.fitmind.trade.mq;

import com.fitmind.common.constants.MqConstants;
import com.fitmind.common.mq.message.OrderPayMessage;
import com.fitmind.common.mq.message.PaySuccessMessage;
import com.fitmind.common.utils.RabbitMqHelper;
import com.fitmind.trade.constants.OrderStatus;
import com.fitmind.trade.constants.TradeConstants;
import com.fitmind.trade.domain.po.TradeOrder;
import com.fitmind.trade.domain.po.TradeOrderDetail;
import com.fitmind.trade.mapper.TradeOrderDetailMapper;
import com.fitmind.api.client.promotion.CouponClient;
import com.fitmind.trade.service.IOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 支付成功通知的消费者（fm-pay 生产）。
 *
 * <p><b>★ 这里用的是「条件更新 → 成功才发消息」这个模式</b>，
 * 它同时解决两件事：</p>
 * <ol>
 *   <li><b>并发</b>：支付回调与超时取消可能同时发生，只有一个能改成功</li>
 *   <li><b>幂等</b>：MQ 是 at-least-once，重复投递时第二次的
 *       {@code changeStatus} 返回 false —— 于是<b>不会重复发 order.pay</b>，
 *       学习侧也就不会重复建课表</li>
 * </ol>
 *
 * <p>常见实现正是在这里栽的：{@code handlePaySuccess} 没有做幂等，
 * MQ 重投就会重复发 {@code order.pay}、重复加课。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaySuccessListener {

    private final IOrderService orderService;
    private final TradeOrderDetailMapper orderDetailMapper;
    private final RabbitMqHelper rabbitMqHelper;
    private final CouponClient couponClient;
    private final com.fitmind.trade.util.CouponIdsHelper couponIdsHelper;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Queue.PAY_SUCCESS_QUEUE, durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.PAY_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.PAY_SUCCESS_KEY
    ))
    public void listenPaySuccess(PaySuccessMessage message) {
        if (message == null || message.getBizOrderNo() == null) {
            log.warn("收到无效的支付成功消息：{}", message);
            return;
        }
        Long orderId = Long.valueOf(message.getBizOrderNo());

        // ★ 条件更新：只有"待支付 → 已支付"成功才继续。
        //   重复投递 / 订单已被超时关闭时，这里返回 false。
        boolean first = orderService.changeStatus(new TradeOrder()
                .setId(orderId)
                .setStatus(OrderStatus.PAYED.getValue())
                .setPayTime(LocalDateTime.now())
                .setPayChannel(message.getPayChannelCode())
                .setPayOrderNo(message.getPayOrderNo()), OrderStatus.NO_PAY);

        if (!first) {
            log.info("订单已是终态，支付成功消息被忽略（幂等）：orderId={}, payOrderNo={}",
                    orderId, message.getPayOrderNo());
            return;
        }

        // 明细跟着改成已支付，并算出课程到期时间
        List<TradeOrderDetail> details = orderDetailMapper.getByOrderId(orderId);
        LocalDateTime now = LocalDateTime.now();
        for (TradeOrderDetail d : details) {
            LocalDateTime expire = d.getValidDuration() == null || d.getValidDuration() <= 0
                    ? null                                  // 永不过期
                    : now.plusDays(d.getValidDuration());
            d.setStatus(TradeConstants.DETAIL_PAYED).setCourseExpireTime(expire);
            orderDetailMapper.updateById(d);
        }

        // ★ 核销优惠券：放在<b>支付成功后</b>而不是下单时。
        //   下单可能不付款，提前核销会把用户的券锁死在一笔注定关闭的订单上。
        //   幂等由促销侧的条件更新保证（只核销"未使用"的）。
        List<Long> couponIds = couponIdsHelper.read(orderId);
        if (!couponIds.isEmpty()) {
            try {
                couponClient.writeOff(java.util.Map.of("ids", couponIds, "orderId", orderId));
                log.info("优惠券已核销：orderId={}, ids={}", orderId, couponIds);
            } catch (Exception e) {
                // 核销失败不阻断主流程：课已经买了，券的事可以人工补。
                // 记 error 日志，后续可对账修复
                log.error("核销优惠券失败（不影响已支付订单）：orderId={}, ids={}", orderId, couponIds, e);
            }
        }

        // 通知学习服务写课表（付费报名的落地动作）
        rabbitMqHelper.send(MqConstants.Exchange.LEARNING_EXCHANGE,
                MqConstants.Key.ORDER_PAY_KEY,
                OrderPayMessage.of(orderId, message.getBizUserId(),
                        details.stream().map(TradeOrderDetail::getCourseId).collect(Collectors.toList()),
                        message.getAmount()));
        log.info("订单支付成功：orderId={}, 课程 {} 门，已通知学习服务", orderId, details.size());
    }
}
