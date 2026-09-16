package com.fitmind.common.autoconfigure.mq;

import com.fitmind.common.utils.RabbitMqHelper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * MQ 工具的自动装配。
 *
 * <p>只有 classpath 上存在 {@code RabbitTemplate}（即引了 amqp starter 的服务）
 * 才会装配 {@link RabbitMqHelper}，网关等模块不受影响。</p>
 *
 * <p><b>为什么这里用 {@code @AutoConfiguration} 而不是 {@code @Configuration}</b>：
 * 这正是 M1 踩过的坑——{@code @ConditionalOnClass} 放在被组件扫描的
 * {@code @Configuration} 上<b>是无效的</b>（类已经被加载了，条件评估太晚，
 * 方法签名里缺失的类型会直接抛 NoClassDefFoundError）。
 * 而 {@code @AutoConfiguration} 注册在
 * {@code META-INF/spring/...AutoConfiguration.imports} 里，
 * Spring Boot 会<b>先读注解元数据再决定是否加载类</b>，条件才真正生效。</p>
 */
@AutoConfiguration
@ConditionalOnClass(RabbitTemplate.class)
public class MqAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RabbitMqHelper rabbitMqHelper(RabbitTemplate rabbitTemplate) {
        return new RabbitMqHelper(rabbitTemplate);
    }

    /**
     * 统一消息格式为 JSON。
     *
     * <p><b>为什么必须显式声明</b>：Spring AMQP 默认用 {@code SimpleMessageConverter}，
     * 它遇到 Long / DTO 会走<b>Java 原生序列化</b>（字节流，contentType 是
     * x-java-serialized-object）。这带来两个问题：</p>
     * <ol>
     *   <li>消息在 RabbitMQ 管理台里看不懂，排障全靠猜</li>
     *   <li>新版 Spring AMQP 对反序列化有类型白名单，跨服务消费容易被拒</li>
     * </ol>
     *
     * <p>放在公共模块而不是各服务里，是为了保证<b>生产方和消费方用同一个转换器</b>——
     * 一边 JSON 一边 Java 序列化，消息就永远解不开了。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public Jackson2JsonMessageConverter jackson2MessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
