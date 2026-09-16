package com.fitmind.gateway.filter;

import cn.hutool.core.util.IdUtil;
import com.fitmind.common.constants.Constant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 请求来源标记过滤器。
 *
 * <p>做两件事：</p>
 * <ol>
 *     <li>打上 {@code request-from: gateway} 标记 —— 业务服务据此判断这是一个
 *         「外部请求」，从而把返回值包装成统一响应 {@code R}；
 *         服务间 Feign 调用不带该标记，返回原始对象。</li>
 *     <li>生成并透传 {@code requestId} —— 一次请求跨多个服务时，日志可用同一个 id
 *         串起来，排障时非常有用。</li>
 * </ol>
 *
 * <p>执行顺序排在鉴权过滤器之前。</p>
 */
@Slf4j
@Component
public class RequestOriginFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1.生成或沿用链路 id
        String requestId = exchange.getRequest().getHeaders().getFirst(Constant.REQUEST_ID_HEADER);
        if (!StringUtils.hasText(requestId)) {
            requestId = IdUtil.fastSimpleUUID();
        }
        // 2.追加来源标记与链路 id
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(Constant.REQUEST_FROM_HEADER, Constant.GATEWAY_ORIGIN_NAME)
                .header(Constant.REQUEST_ID_HEADER, requestId)
                .build();
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
