package com.fitmind.common.autoconfigure.mq;

import com.fitmind.common.utils.RabbitMqHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
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
 *
 * <p>★ 2026-11-07（P0-3 / P0-4）在此补了两块"可靠性"能力：
 * 生产端的 <b>confirm + return</b>、消费端的<b>重试耗尽 → 死信</b>。
 * 在此之前这套 MQ 只能保证"消息发出去了"，不能保证"消息送到了、也没丢"。</p>
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(RabbitTemplate.class)
public class MqAutoConfiguration {

    /** 死信交换机（独立于业务域交换机，见 {@code mqDeadLetterTopology} 的说明） */
    public static final String DLX_EXCHANGE = "dlx.exchange";

    /** 死信队列 */
    public static final String DLQ_QUEUE = "dead.letter.queue";

    /** 死信路由键 */
    public static final String DLQ_KEY = "dead.letter";

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

    /**
     * ★ P0-3：生产端确认——让"消息没送到"变成一个<b>能被看见</b>的事件。
     *
     * <p><b>为什么必须有这一块</b>：{@code RabbitMqHelper#send} 里传了
     * {@code CorrelationData}，但此前<b>没有任何回调接收结果</b>——
     * 它只是一个随机 id，传了等于没传。表现是：交换机不存在、路由键拼错时，
     * 消息被 broker <b>静默丢弃</b>，业务代码零感知。</p>
     *
     * <p>两道保险，覆盖面不同：</p>
     * <ol>
     *   <li><b>confirm</b>：broker 是否收下了这条消息（不管有没有队列接）；</li>
     *   <li><b>return</b>：消息到了交换机但<b>没有队列匹配</b>路由键，被退回。</li>
     * </ol>
     * <p>最容易漏的是第 2 种：交换机在、队列被误删，confirm 依然成功，
     * 只有 return 才知道"没人收"。所以 {@code spring.rabbitmq.template.mandatory}
     * 必须配上，否则 returns 回调根本不会触发。</p>
     *
     * <p>⚠️ 这里只<b>记录</b>，不做重投。真正的"零丢失"要靠本地消息表 + 定时补发
     * （P1）；本轮目标是"把静默丢失变成有日志的丢失"。</p>
     */
    @Bean
    @ConditionalOnMissingBean(name = "mqConfirmCustomizer")
    public RabbitTemplateCustomizer mqConfirmCustomizer() {
        return template -> {
            template.setConfirmCallback((correlation, ack, cause) -> {
                if (!ack) {
                    log.error("MQ 投递未确认（broker 拒收或不可达）：id={}, cause={}",
                            correlation == null ? null : correlation.getId(), cause);
                }
            });
            template.setReturnsCallback(returned -> log.error(
                    "MQ 路由失败（消息无人接收，已退回）：exchange={}, routingKey={}, replyCode={}, replyText={}",
                    returned.getExchange(), returned.getRoutingKey(),
                    returned.getReplyCode(), returned.getReplyText()));
        };
    }

    /**
     * ★ P0-4：死信拓扑。
     *
     * <p><b>为什么是"新建一套"而不是给业务队列加 x-dead-letter-exchange</b>：
     * RabbitMQ <b>不允许</b>用不同参数重新声明已存在的队列，会抛
     * {@code PRECONDITION_FAILED - inequivalent arg 'x-dead-letter-exchange'}，
     * 症状是"服务直接起不来"。给 30 个已存在的业务队列加参数，
     * 等于要求"停服 → 逐个删队列 → 重启"，风险远大于收益。
     * 这里改成<b>新增</b>一套独立死信拓扑，与业务队列零耦合。</p>
     *
     * <p><b>为什么 DirectExchange 而不是 Topic</b>：死信只有一个去向，
     * 固定 routingKey = {@code dead.letter} 就够，topic 的匹配能力用不上。</p>
     */
    @Bean
    @ConditionalOnMissingBean(name = "mqDeadLetterTopology")
    public Declarables mqDeadLetterTopology() {
        DirectExchange dlx = new DirectExchange(DLX_EXCHANGE, true, false);
        Queue dlq = QueueBuilder.durable(DLQ_QUEUE).build();
        Binding binding = BindingBuilder.bind(dlq).to(dlx).with(DLQ_KEY);
        log.info("MQ 死信拓扑已声明：exchange={}, queue={}, routingKey={}",
                DLX_EXCHANGE, DLQ_QUEUE, DLQ_KEY);
        return new Declarables(dlx, dlq, binding);
    }

    /**
     * ★ P0-4：重试耗尽后的去处——转投死信队列。
     *
     * <p><b>为什么不能只配 {@code default-requeue-rejected: false} 就完事</b>：
     * 那样重试用尽的消息会被<b>直接丢弃</b>——"有上限"反而变成"更容易丢"。
     * 必须同时给出 recoverer，让"停下来"之后还有个去处。</p>
     *
     * <p>Spring Boot 的 {@code RabbitAnnotationDrivenConfiguration} 只要发现容器里有
     * {@code MessageRecoverer} Bean，就会把它装进重试拦截器——所以这里必须是
     * <b>Bean</b>。只写 yml 不写 Bean，消息依旧是丢的。</p>
     *
     * <p>转投时会带上原始 exchange/routingKey、异常类型与堆栈（x-exception-* 头），
     * 排查"这条消息为什么失败"不用再回去翻当时的日志。</p>
     */
    @Bean
    @ConditionalOnMissingBean(MessageRecoverer.class)
    public MessageRecoverer messageRecoverer(RabbitTemplate rabbitTemplate) {
        return new RepublishMessageRecoverer(rabbitTemplate, DLX_EXCHANGE, DLQ_KEY);
    }
}
