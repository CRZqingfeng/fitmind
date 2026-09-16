package com.fitmind.learning.config;

import com.fitmind.api.client.user.UserClient;
import com.fitmind.common.exceptions.ForbiddenException;
import com.fitmind.common.exceptions.UnauthorizedException;
import com.fitmind.common.utils.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端权限拦截器。
 *
 * <p>为什么要单独加这一层：常见实现的 {@code /admin/**} 只受<b>登录</b>保护 ——
 * 任何登录用户都能调管理端接口（包括隐藏内容、查别人的信息）。
 * 这里补上角色校验：必须 {@code type = 1}（管理员）。</p>
 *
 * <p>管理端 QPS 极低，每次实查一次 {@code userType} 即可；
 * 不做本地缓存，避免引入"权限改了缓存没失效"这类新问题。</p>
 *
 * <p>⚠️ {@code @Lazy} 不是装饰：{@link WebMvcConfig} 是 {@code WebMvcConfigurer}，
 * Spring 在初始化 MVC 时就会创建它 —— 如果此时强制初始化 {@code UserClient}
 * （Feign 的 FactoryBean 需要完整的上下文），会形成
 * 「WebMvc 初始化 → interceptor → Feign 客户端 → 需要 MVC 基础设施」的循环依赖而启动失败。
 * 加 {@code @Lazy} 后注入的是代理，真正用到时才初始化。</p>
 */
@Slf4j
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    /** fm_user 的用户类型：1-管理员 2-学员 3-教练 */
    private static final int TYPE_ADMIN = 1;

    private final UserClient userClient;

    public AdminAuthInterceptor(@Lazy UserClient userClient) {
        this.userClient = userClient;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new UnauthorizedException("未登录");
        }
        Integer userType = userClient.queryUserType(userId);
        if (userType == null || userType != TYPE_ADMIN) {
            log.warn("非管理员访问管理端接口：userId={}, userType={}, uri={}",
                    userId, userType, request.getRequestURI());
            throw new ForbiddenException("无权访问管理端接口");
        }
        return true;
    }
}
