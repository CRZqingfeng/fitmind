package com.fitmind.course.config;

import com.fitmind.common.constants.MqConstants;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MQ 配置。
 *
 * <p>生产者只负责声明<b>交换机</b>，队列与绑定由消费者（fm-search，M2-3）声明——
 * 这是 RabbitMQ 的惯例：谁消费谁决定队列怎么配（是否持久化、是否死信、预取多少）。</p>
 *
 * <p>如果这边也声明队列，两边配置一旦不一致，启动时会报
 * {@code PRECONDITION_FAILED}（队列参数冲突），反而添乱。</p>
 */
@Configuration
public class MqConfig {

    @Bean
    public TopicExchange courseExchange() {
        // durable=true（重启不丢）、autoDelete=false（无消费者时不自动删）
        return new TopicExchange(MqConstants.Exchange.COURSE_EXCHANGE, true, false);
    }
}
