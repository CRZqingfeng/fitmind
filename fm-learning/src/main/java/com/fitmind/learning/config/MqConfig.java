package com.fitmind.learning.config;

import com.fitmind.common.constants.MqConstants;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MQ 配置。
 *
 * <p>生产者只声明<b>交换机</b>，队列与绑定由消费者（M3-2 积分模块）声明——
 * 谁消费谁决定队列怎么配。与 fm-course 的 MqConfig 同一个模式。</p>
 */
@Configuration
public class MqConfig {

    @Bean
    public TopicExchange learningExchange() {
        // durable=true（重启不丢）、autoDelete=false
        return new TopicExchange(MqConstants.Exchange.LEARNING_EXCHANGE, true, false);
    }
}
