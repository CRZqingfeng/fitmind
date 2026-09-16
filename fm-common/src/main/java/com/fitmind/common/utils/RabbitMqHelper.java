package com.fitmind.common.utils;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.UUID;

/**
 * RabbitMQ 发送工具。
 *
 * <p>三个能力：</p>
 * <ol>
 *     <li>{@link #send} —— 同步发送（在事务里用，发失败能跟着事务回滚）</li>
 *     <li>{@link #sendDelay} —— 延迟消息（M3 做课程过期提醒会用）</li>
 *     <li>{@link #sendAsync} —— 异步发送，不阻塞主流程（上下架通知搜索服务用这个）</li>
 * </ol>
 *
 * <p><b>为什么异步发送还要传 requestId</b>：异步线程会丢失 MDC（日志链路 id），
 * 消息消费时报错就串不起链路了。所以发送前先把 requestId 抓出来，在子线程里塞回去。</p>
 */
@Slf4j
public class RabbitMqHelper {

    /** 消息头：链路追踪 id */
    private static final String REQUEST_ID_HEADER = "requestId";

    private final RabbitTemplate rabbitTemplate;

    /** 给消息带上链路 id */
    private final MessagePostProcessor processor = message -> {
        MessageProperties props = message.getMessageProperties();
        String requestId = MDC.get(REQUEST_ID_HEADER);
        if (requestId != null) {
            props.setHeader(REQUEST_ID_HEADER, requestId);
        }
        return message;
    };

    /** 异步发送用的线程池 */
    private final ThreadPoolTaskExecutor executor;

    public RabbitMqHelper(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
        this.executor = new ThreadPoolTaskExecutor();
        this.executor.setCorePoolSize(2);
        this.executor.setMaxPoolSize(8);
        this.executor.setQueueCapacity(500);
        this.executor.setThreadNamePrefix("mq-async-");
        // 队列满了让调用线程自己跑，不要丢消息
        this.executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        this.executor.initialize();
    }

    /** 同步发送 */
    public <T> void send(String exchange, String routingKey, T message) {
        log.debug("发送 MQ 消息：exchange={}, routingKey={}, message={}", exchange, routingKey, message);
        String id = UUID.randomUUID().toString().replace("-", "");
        rabbitTemplate.convertAndSend(exchange, routingKey, message, processor, new CorrelationData(id));
    }

    /** 延迟发送（延迟时间由交换机的延迟类型决定，这里只透传消息） */
    public <T> void sendDelay(String exchange, String routingKey, T message, long delayMillis) {
        log.debug("发送延迟 MQ 消息：exchange={}, routingKey={}, delay={}ms", exchange, routingKey, delayMillis);
        String id = UUID.randomUUID().toString().replace("-", "");
        rabbitTemplate.convertAndSend(exchange, routingKey, message,
                msg -> {
                    processor.postProcessMessage(msg);
                    msg.getMessageProperties().setDelayLong(delayMillis);
                    return msg;
                },
                new CorrelationData(id));
    }

    /**
     * 异步发送：不阻塞调用方。
     *
     * <p>用在「课程上下架后通知搜索服务」这类场景——
     * 搜索索引晚几百毫秒同步没关系，但不能因此拖慢上架接口。</p>
     */
    /**
     * 事务提交后再异步发送。
     *
     * <p><b>为什么需要这个</b>：如果在 {@code @Transactional} 方法里直接发消息，
     * 消息可能在<b>事务还没提交时</b>就到了消费者手里。消费者拿着 id 回头查数据，
     * 查到的还是提交前的旧状态——课程上架时这个坑会表现成
     * 「消费者查不到课程，索引同步失败」。</p>
     *
     * <p>这不是理论问题：M2-3 验收时上下架消息偶发同步失败，
     * 就是这个竞态导致的（时快时慢，非常难查）。</p>
     *
     * <p>没有事务在跑时（比如定时任务里调用）就退化成直接异步发送。</p>
     */
    public <T> void sendAfterCommit(String exchange, String routingKey, T message) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendAsync(exchange, routingKey, message);
                }
            });
            log.debug("消息已挂到事务提交后发送：exchange={}, routingKey={}", exchange, routingKey);
        } else {
            sendAsync(exchange, routingKey, message);
        }
    }

    public <T> void sendAsync(String exchange, String routingKey, T message) {
        String requestId = MDC.get(REQUEST_ID_HEADER);
        CompletableFuture.runAsync(() -> {
            try {
                if (requestId != null) {
                    MDC.put(REQUEST_ID_HEADER, requestId);
                }
                send(exchange, routingKey, message);
            } catch (Exception e) {
                // 异步发送失败不能影响主流程，但要留下明确日志便于排查
                log.error("异步发送 MQ 消息失败：exchange={}, routingKey={}, message={}",
                        exchange, routingKey, message, e);
            } finally {
                MDC.remove(REQUEST_ID_HEADER);
            }
        }, executor);
    }
}
