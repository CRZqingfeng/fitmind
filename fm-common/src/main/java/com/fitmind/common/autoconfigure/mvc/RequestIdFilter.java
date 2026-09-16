package com.fitmind.common.autoconfigure.mvc;

import com.fitmind.common.constants.Constant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求链路 id 过滤器。
 *
 * <p>网关为每个外部请求生成 {@code requestId} 并放在请求头里。本过滤器把它
 * 放进 SLF4J 的 {@link MDC}，这样日志格式里带上 {@code %X{requestId}} 就能
 * 把一次请求在各服务中产生的日志串起来。</p>
 *
 * <p>执行完会清理 MDC（线程复用，不清理会串号）。</p>
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(Constant.REQUEST_ID_HEADER);
        if (StringUtils.hasText(requestId)) {
            MDC.put(Constant.REQUEST_ID_HEADER, requestId);
            // 回写到响应头，前端也能拿到，便于问题反馈时带上
            response.setHeader(Constant.REQUEST_ID_HEADER, requestId);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(Constant.REQUEST_ID_HEADER);
        }
    }
}
