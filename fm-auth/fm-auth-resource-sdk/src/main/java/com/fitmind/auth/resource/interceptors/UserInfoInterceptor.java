package com.fitmind.auth.resource.interceptors;

import com.fitmind.auth.common.constants.AuthConstants;
import com.fitmind.common.utils.TokenContext;
import com.fitmind.common.utils.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 用户信息拦截器。
 *
 * <p>网关验签成功后会把用户 id 放在请求头 {@code user-info} 里，
 * 本拦截器把它取出来存进 {@link UserContext}，这样业务代码在任何地方
 * 都能用 {@code UserContext.getUser()} 拿到当前登录用户，不用层层传参。</p>
 *
 * <p>同时在请求结束时清理 ThreadLocal —— Tomcat 线程是复用的，
 * 不清理会导致下一个请求读到上一个用户的 id（串号）。</p>
 */
@Slf4j
public class UserInfoInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 1.取用户信息请求头
        String userInfo = request.getHeader(AuthConstants.USER_HEADER);
        if (userInfo == null || userInfo.isBlank()) {
            return true;
        }
        // 2.解析为用户 id 并存入上下文
        try {
            Long userId = Long.valueOf(userInfo);
            UserContext.setUser(userId);
            TokenContext.setToken(request.getHeader(AuthConstants.TOKEN_HEADER));
        } catch (NumberFormatException e) {
            log.error("用户身份信息格式不正确：{}，原因：{}", userInfo, e.getMessage());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 3.清理，防止线程复用导致用户信息串号
        UserContext.removeUser();
        TokenContext.removeToken();
    }
}
