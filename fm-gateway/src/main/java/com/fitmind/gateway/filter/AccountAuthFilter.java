package com.fitmind.gateway.filter;

import com.fitmind.auth.common.constants.AuthConstants;
import com.fitmind.auth.gateway.util.AuthUtil;
import com.fitmind.common.domain.R;
import com.fitmind.common.domain.dto.LoginUserDTO;
import com.fitmind.gateway.config.AuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 账号鉴权过滤器（网关全局过滤器）。
 *
 * <p>网关是所有外部请求的唯一入口，鉴权在此统一完成，业务服务无需重复造轮子。
 * 处理流程：</p>
 * <ol>
 *     <li><b>免鉴权路径</b>（登录、注册、发验证码等）直接放行，不做任何解析；</li>
 *     <li>否则解析 {@code Authorization} 头中的 token（RS256 公钥验签）；</li>
 *     <li>解析成功 → 把 {@code user-info}(userId) 与 {@code token-info}(token)
 *         两个请求头<b>追加到请求上</b>再转发，下游靠它识别当前用户；</li>
 *     <li>解析失败且该路径<b>要求登录</b> → 直接返回 401，不再转发（快速失败，
 *         避免无效流量打到业务服务）。</li>
 * </ol>
 *
 *
 */
@Slf4j
@Component
public class AccountAuthFilter implements GlobalFilter, Ordered {

    private final AuthUtil authUtil;
    private final AuthProperties authProperties;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    public AccountAuthFilter(AuthUtil authUtil, AuthProperties authProperties) {
        this.authUtil = authUtil;
        this.authProperties = authProperties;
    }

    /**
     * 启动时打印实际生效的鉴权配置。
     *
     * <p>必须打印：配置如果没绑上（列表为空），所有请求都会被静默放行，
     * 表现是"接口看着正常但谁都能调"，很难排查。</p>
     */
    @jakarta.annotation.PostConstruct
    public void logAuthConfig() {
        log.info("网关鉴权配置：免鉴权 {} 条 {}，需登录 {} 条 {}",
                authProperties.getExcludePath().size(), authProperties.getExcludePath(),
                authProperties.getRequireLoginPath().size(), authProperties.getRequireLoginPath());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String method = request.getMethod().name();
        // ⚠️ 必须用「原始请求路径」，不能用 getPath()：
        //    网关的路由匹配 + StripPrefix=1 会把第一层前缀（如 /ms）剥掉，
        //    本过滤器执行时 request.getPath() 已经只剩 /medias，
        //    而 requireLoginPath 配的是带前缀的 /ms/** —— 那样一条都匹配不上，
        //    结果是「配置写得很全，实际全部放行」的静默失效。
        //    contextPath() / getURI() 同样拿不到前缀（均已实测排除），
        //    只有路由匹配阶段存下来的 GATEWAY_ORIGINAL_REQUEST_URL_ATTR 是完整的。
        // 注意：这个 attribute 的类型是 Set<URI>（不是单个 URI），取第一个即可
        Set<URI> originalUris = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
        String path = (originalUris != null && !originalUris.isEmpty())
                ? originalUris.iterator().next().getPath()
                : request.getPath().pathWithinApplication().value();
        String antPath = method + ":" + path;

        // 1.免鉴权路径：直接放行
        if (isExcludePath(antPath) || isExcludePath(path)) {
            return chain.filter(exchange);
        }

        // 2.解析 token
        String token = request.getHeaders().getFirst(AuthConstants.AUTHORIZATION_HEADER);
        R<LoginUserDTO> result = authUtil.parseToken(token);

        // 3.解析成功：把用户信息透传给下游
        if (result.isSuccess()) {
            Long userId = result.getData().getUserId();
            ServerWebExchange mutated = exchange.mutate()
                    .request(builder -> builder
                            .header(AuthConstants.USER_HEADER, String.valueOf(userId))
                            .header(AuthConstants.TOKEN_HEADER, token))
                    .build();
            return chain.filter(mutated);
        }

        // 4.解析失败：若该路径要求登录，直接 401 拦下
        log.info("鉴权判定：path={} antPath={} token={} parseSuccess={} requireLogin={}",
                path, antPath, (token == null ? "无" : "有"),
                result.isSuccess(),
                (isRequireLoginPath(antPath) || isRequireLoginPath(path)));
        if (isRequireLoginPath(antPath) || isRequireLoginPath(path)) {
            log.warn("未认证请求被拦截：{} {}，原因：{}", method, path, result.getMsg());
            return unauthorized(exchange, result);
        }

        // 5.既不要求登录、也不在免鉴权名单：放行（由业务服务自行决定）
        return chain.filter(exchange);
    }

    private boolean isExcludePath(String antPath) {
        return matchAny(authProperties.getExcludePath(), antPath);
    }

    private boolean isRequireLoginPath(String antPath) {
        return matchAny(authProperties.getRequireLoginPath(), antPath);
    }

    private boolean matchAny(List<String> patterns, String antPath) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            if (antPathMatcher.match(pattern, antPath)) {
                return true;
            }
        }
        return false;
    }

    /** 返回 401 统一响应 */
    private Mono<Void> unauthorized(ServerWebExchange exchange, R<LoginUserDTO> result) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        String body = String.format("{\"code\":%d,\"msg\":\"%s\",\"data\":null}",
                result.getCode(), result.getMsg());
        return response.writeWith(Mono.just(
                response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public int getOrder() {
        // 排在请求来源标记过滤器之后
        return 1000;
    }
}
