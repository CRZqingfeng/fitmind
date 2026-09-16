package com.fitmind.remark.config;

import com.fitmind.common.constants.MqConstants;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 点赞服务的 MQ 声明。
 *
 * <p>生产方只声明交换机；队列由消费方（fm-learning）声明 —— 谁消费谁决定队列怎么配。</p>
 */
@Configuration
public class MqConfig {

    @Bean
    public TopicExchange likeExchange() {
        // durable=true（重启不丢）、autoDelete=false
        return new TopicExchange(MqConstants.Exchange.LIKE_EXCHANGE, true, false);
    }
}
