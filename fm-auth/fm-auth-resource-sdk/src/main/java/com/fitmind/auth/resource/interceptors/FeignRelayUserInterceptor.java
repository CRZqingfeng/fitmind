package com.fitmind.auth.resource.interceptors;

import com.fitmind.auth.common.constants.AuthConstants;
import com.fitmind.common.utils.TokenContext;
import com.fitmind.common.utils.UserContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * Feign 用户信息透传拦截器。
 *
 * <p>服务 A 在处理「用户 X 的请求」时又通过 Feign 调用了服务 B，
 * 这次内部调用默认是不带用户信息的。本拦截器把当前用户 id 与 token
 * 继续透传给下游，保证服务 B 也能通过 {@code UserContext} 拿到用户 X。</p>
 */
public class FeignRelayUserInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        Long userId = UserContext.getUser();
        if (userId != null) {
            template.header(AuthConstants.USER_HEADER, String.valueOf(userId));
        }
        String token = TokenContext.getToken();
        if (token != null && !token.isBlank()) {
            template.header(AuthConstants.TOKEN_HEADER, token);
        }
    }
}
