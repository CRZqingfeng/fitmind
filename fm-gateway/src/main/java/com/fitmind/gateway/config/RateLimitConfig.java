package com.fitmind.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * 限流的"按什么维度分桶"（P0-6）。
 *
 * <p><b>RequestRateLimiter 只负责数令牌，按什么分组由 KeyResolver 决定。</b>
 * 这里统一用 IP：登录、发码、注册都是"陌生人可打"的接口，
 * 未登录时根本没有用户维度可用。</p>
 *
 * <p>★★ <b>为什么要优先取 X-Forwarded-For</b>：
 * 网关前面挂 Nginx 时，{@code remoteAddress} 永远是 <b>Nginx 的 IP</b>。
 * 不加这一层判断，全站会共用一个令牌桶 —— 第一个被限流掉的是所有正常用户，
 * 而攻击者只是"和大家一起被限"，完全没被针对。
 * 这是限流"配了等于没配"最常见的原因。</p>
 *
 * <p>⚠️ <b>X-Forwarded-For 可以被客户端伪造</b>。在本项目的部署形态下
 * （网关不直接对公网开放、由反代设置该头），这个风险可接受；
 * 若将来网关直接对外，必须改成"只信任反代网段发来的 XFF"
 * （或者干脆换 HTTP 连接的真实源 IP）。这条边界必须写进文档，不能默认它安全。</p>
 */
@Configuration
public class RateLimitConfig {

    /**
     * 按客户端 IP 限流。
     *
     * <p>返回 {@code "unknown"} 而不是空：空 key 会让 RequestRateLimiter 直接拒绝请求
     * （deny-empty-key 默认 true），那样"拿不到 IP"就变成了"全站不可用"。</p>
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (StringUtils.hasText(forwarded)) {
                // 形如 "client, proxy1, proxy2" —— 第一个是最初的客户端
                return Mono.just(forwarded.split(",")[0].trim());
            }
            InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
            return Mono.just(remote == null ? "unknown" : remote.getAddress().getHostAddress());
        };
    }
}
