package com.fitmind.trade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 交易服务启动类。
 *
 * <p>交易为什么必须独立成一个服务，而不是塞进 fm-learning：</p>
 * <ol>
 *   <li><b>职责边界</b>：学习服务管"怎么练"，交易服务管"能不能练"。报名是两者的交界，
 *       但它由「订单支付成功」驱动，所以触发方在交易侧、落库方在学习侧</li>
 *   <li><b>写路径的并发特征不同</b>：下单是短促的并发写（抢购、重复点击），
 *       学习进度是长连接持续上报。混在一起时，一次下单风暴会拖慢所有人的学习上报</li>
 *   <li><b>数据归属</b>：订单与金额属于交易的库，学习侧只存"我报了哪些课"</li>
 * </ol>
 *
 * <p><b>★ 订单超时取消为什么用 Redis ZSet 延迟队列 + XXL-JOB，而不是 RabbitMQ 延迟队列</b>：
 * 常见实现用 MQ 延迟队列，是为了<b>反复轮询第三方支付的结果</b>（回调可能丢）。
 * 我们的支付是 Mock 的（M4-2），"支付成功"由我们自己主动触发，不需要反复去问；
 * 真正需要的只是"到期了把订单关掉"。而这类"到点做一件事"的需求，
 * 我们在 M3-1 已经用 Redis ZSet + ZREM 原子抢占做过一遍（课表过期、进度合并），
 * 这里复用同一套模式 —— <b>同一个问题用同一种解法</b>，少维护一套机制。</p>
 *
 * <p>{@code scanBasePackages = "com.fitmind"} 用于扫描 fm-common 中的全局异常处理器、
 * MQ 工具、XXL-JOB 执行器等公共组件。</p>
 *
 * <p>⚠️ <b>新模块的四个必备项</b>（每一条都是真踩过的）：</p>
 * <ol>
 *   <li>pom 里必须引 {@code spring-cloud-starter-openfeign}（<b>哪怕自己不写客户端</b>）——
 *       fm-api 的 {@code FeignOriginConfig} 会被组件扫描扫到，缺依赖启动即
 *       {@code NoClassDefFoundError: feign/RequestInterceptor}</li>
 *   <li>pom 里必须引 {@code spring-cloud-starter-loadbalancer} ——
 *       Spring Cloud 2020 起 openfeign 不再自带它，缺了报
 *       {@code No Feign Client for loadBalancing defined}（本模块栽在这）</li>
 *   <li>要用 {@code CourseClient} 这类客户端，必须加 {@code @EnableFeignClients} ——
 *       否则报 {@code No qualifying bean of type CourseClient available}（本模块也栽在这）</li>
 *   <li>在 {@code WebMvcConfigurer} 里注册的拦截器若依赖 Feign 客户端，构造函数要加
 *       {@code @Lazy}（否则 MVC 初始化时成环）</li>
 * </ol>
 */
@SpringBootApplication(scanBasePackages = "com.fitmind")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.fitmind")
public class TradeApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradeApplication.class, args);
    }
}
