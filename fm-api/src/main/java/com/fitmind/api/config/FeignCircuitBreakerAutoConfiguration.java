package com.fitmind.api.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Feign 熔断的默认参数（P0-5）。
 *
 * <p><b>为什么需要它</b>：光开 {@code openfeign.circuitbreaker.enabled} 只是让
 * fallback 能触发，参数还是 resilience4j 的默认值 —— 而默认
 * {@code minimumNumberOfCalls = 100} 意味着"<b>连续失败 100 次才开始熔断</b>"。
 * 对小流量服务和演示环境，这等于"永远不会熔断"，
 * 于是这条能力会变成"配了但看不到效果"——比没配更容易让人误判。</p>
 *
 * <p>★★ <b>必须同时配 TimeLimiter，否则会踩一个大坑</b>：
 * Spring Cloud CircuitBreaker 的 Resilience4J 实现<b>默认带 1 秒的 TimeLimiter</b>，
 * 它会把超过 1 秒的调用直接掐断 —— 于是"开了熔断"表现成"接口大面积超时"，
 * 而且看起来像网络问题，很难联想到是熔断配置。
 * 这里把 TimeLimiter 放到 10 秒（<b>大于</b> Feign 的 read-timeout 5 秒），
 * 让超时的第一道闸门永远是 Feign 自己 —— 那才是我们精心分层过的超时
 * （网关 10s > Feign 5s）。TimeLimiter 只当"兜底"，不当主力。</p>
 *
 * <p>★★ <b>这里的数字是"演示友好"，不是"工业最优"</b>：
 * 窗口 20 / 最少 10 次 / 失败率 50%，是为了让"下游挂掉 → 十几秒内快速失败"
 * 这个行为在本机能被真实观察到。真实阈值必须靠压测的 P99 与错误率反推，
 * 拿到数据之前，任何具体数字都只是猜。别把 10 当结论。</p>
 */
@AutoConfiguration
@ConditionalOnClass(name = "io.github.resilience4j.circuitbreaker.CircuitBreaker")
public class FeignCircuitBreakerAutoConfiguration {

    /**
     * 对所有未单独指定 id 的 Feign 客户端生效。
     *
     * <p>★ 为什么用 {@code configureDefault} 而不是 15 份 yml：
     * 同一组参数在 15 个服务里重复写 15 遍，改一次要动 15 个文件，
     * 漏一个就是"这个服务的熔断行为跟别人不一样"——这种不一致极难发现。</p>
     */
    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> fitmindCircuitBreakerDefaults() {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(20)
                        // ★ 至少 10 次才统计：否则"1 次失败 = 100% 失败率"会立刻误熔断
                        .minimumNumberOfCalls(10)
                        .failureRateThreshold(50f)
                        // 打开 10 秒后转半开，放 5 个请求试探
                        .waitDurationInOpenState(Duration.ofSeconds(10))
                        .permittedNumberOfCallsInHalfOpenState(5)
                        // 到点自动转半开，不用等下一个请求"顺手"触发探测
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        // ⚠️ 必须 > Feign read-timeout(5s)，理由见类注释
                        .timeoutDuration(Duration.ofSeconds(10))
                        .build())
                .build());
    }
}
