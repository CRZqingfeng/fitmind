package com.fitmind.auth.resource.interceptors;

import com.fitmind.auth.common.constants.AuthErrorInfo;
import com.fitmind.common.exceptions.UnauthorizedException;
import com.fitmind.common.utils.UserContext;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录校验拦截器。
 *
 * <p>需要登录的路径会经过本拦截器：若 {@link UserContext} 中没有用户，
 * 说明请求未携带有效 token，直接抛 401。</p>
 *
 * <p>是否启用、拦截哪些路径由 {@code fm.auth.resource.*} 配置决定，
 * 见 {@code ResourceInterceptorConfiguration}。</p>
 */
public class LoginAuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                             jakarta.servlet.http.HttpServletResponse response,
                             Object handler) {
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new UnauthorizedException(
                    AuthErrorInfo.Code.NOT_LOGIN_CODE, AuthErrorInfo.Msg.NOT_LOGIN);
        }
        return true;
    }
}
