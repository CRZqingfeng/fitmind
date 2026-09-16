package com.fitmind.trade.job;

import com.fitmind.trade.config.TradeProperties;
import com.fitmind.trade.constants.OrderStatus;
import com.fitmind.trade.constants.TradeConstants;
import com.fitmind.trade.domain.po.TradeOrder;
import com.fitmind.trade.mapper.TradeOrderMapper;
import com.fitmind.trade.service.IOrderService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 订单超时取消任务（XXL-JOB，每分钟）。
 *
 * <p><b>★ 为什么不是 RabbitMQ 延迟队列</b>：常见实现用 MQ 延迟队列是为了
 * 「反复轮询第三方支付结果」（回调可能丢）。我们的支付是 Mock 的（M4-2），
 * 回调由自己主动触发，不需要反复去问；真正需要的只是"到点了把订单关掉"。
 * 而"到点做一件事"这类需求，M3-1 已经用 Redis ZSet + ZREM 原子抢占做过一遍，
 * 这里复用同一套机制 —— <b>同一个问题用同一种解法</b>。</p>
 *
 * <p><b>两条路都走，互为保险</b>：</p>
 * <ol>
 *   <li><b>主路径</b>：ZSet 延迟队列里到期的（精确、只扫该处理的）</li>
 *   <li><b>兜底</b>：直接扫库里"超时未支付"的（Redis 重启/被清空时队列会丢，
 *       这时候只有扫库能救）</li>
 * </ol>
 *
 * <p>正确性<b>不依赖"只跑一次"</b>：ZREM 是原子抢占（多实例只有一个能拿到），
 * 状态变更又是条件更新（<code>WHERE status = 待支付</code>）。所以路由策略用
 * FAILOVER 就够 —— 不像 M3-2 的榜单结算那样必须分片广播。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutCancelJob {

    private final TradeProperties tradeProperties;
    private final StringRedisTemplate redisTemplate;
    private final TradeOrderMapper orderMapper;
    private final IOrderService orderService;

    @XxlJob("orderTimeoutCancelJob")
    public void orderTimeoutCancelJob() {
        int fromDelay = closeFromDelayQueue();
        int fromScan = closeFromBackupScan();
        String msg = String.format("订单超时关闭完成：延迟队列 %d 笔 + 兜底扫描 %d 笔 = %d 笔",
                fromDelay, fromScan, fromDelay + fromScan);
        XxlJobHelper.log(msg);
        log.info("[XXL-JOB] {}", msg);
    }

    /**
     * 主路径：处理延迟队列里已到期的订单。
     *
     * <p>{@code ZREM} 的返回值是关键 —— 它是"抢占"这个动作的结果。
     * 返回 0 说明别的实例已经把它摘走了，本次直接跳过，不会重复处理。</p>
     */
    private int closeFromDelayQueue() {
        int batch = tradeProperties.getOrder().getTimeoutCancelBatch();
        long now = System.currentTimeMillis();
        Set<String> candidates = redisTemplate.opsForZSet()
                .rangeByScore(TradeConstants.ORDER_DELAY_KEY, 0, now, 0, batch);
        if (CollectionUtils.isEmpty(candidates)) {
            return 0;
        }
        int closed = 0;
        for (String orderIdStr : candidates) {
            Long removed = redisTemplate.opsForZSet()
                    .remove(TradeConstants.ORDER_DELAY_KEY, orderIdStr);
            if (removed == null || removed == 0) {
                continue;
            }
            try {
                if (orderService.closeTimeoutOrder(Long.valueOf(orderIdStr))) {
                    closed++;
                }
            } catch (Exception e) {
                // 单笔失败不影响整批：下一分钟兜底扫描还会捞到它
                log.error("关闭超时订单失败：orderId={}", orderIdStr, e);
            }
        }
        return closed;
    }

    /**
     * 兜底路径：扫库里"创建时间早于 now - (支付超时 + 宽限)"且仍待支付的订单。
     *
     * <p>宽限的意义是<b>不与主路径抢</b>：正常到期的订单已经被延迟队列处理了，
     * 兜底只负责那些"漏网的"。</p>
     */
    private int closeFromBackupScan() {
        TradeProperties.Order cfg = tradeProperties.getOrder();
        LocalDateTime deadline = LocalDateTime.now()
                .minusMinutes(cfg.getPayOverMinutes() + cfg.getTimeoutScanGraceMinutes());
        List<TradeOrder> timeouts = orderMapper.queryTimeoutOrders(
                OrderStatus.NO_PAY.getValue(), deadline, cfg.getTimeoutCancelBatch());
        if (CollectionUtils.isEmpty(timeouts)) {
            return 0;
        }
        int closed = 0;
        for (TradeOrder order : timeouts) {
            try {
                if (orderService.closeTimeoutOrder(order.getId())) {
                    closed++;
                    // 顺手把延迟队列里的残留清掉，免得下一轮又被捞出来
                    redisTemplate.opsForZSet().remove(
                            TradeConstants.ORDER_DELAY_KEY, order.getId().toString());
                }
            } catch (Exception e) {
                log.error("兜底关闭超时订单失败：orderId={}", order.getId(), e);
            }
        }
        return closed;
    }
}
