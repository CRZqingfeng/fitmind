package com.fitmind.gateway.exception.handler;

import com.fitmind.common.constants.ErrorInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关统一异常处理。
 *
 * <p>保证网关自身抛出的异常（超时、连接失败、服务不可用等）也返回和其它服务
 * 一致的 {@code {code,msg,data}} 结构，前端只需一套解析逻辑。</p>
 */
@Slf4j
@Order(-1)
@Component
public class GatewayExceptionHandler implements ErrorWebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        log.error("网关异常：{} {}", exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(), ex);

        var response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex);
        }
        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String msg = ex.getMessage() == null ? ErrorInfo.Msg.SYSTEM_ERROR : "服务暂时不可用，请稍后再试";
        String body = String.format("{\"code\":%d,\"msg\":\"%s\",\"data\":null}",
                ErrorInfo.Code.SERVER_ERROR, msg);
        return response.writeWith(Mono.just(
                response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}
