package com.fitmind.data.config;

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
 * <p><b>★ 为什么数据中心也需要它</b>：运营看板上是<b>整个平台的经营数据</b> ——
 * 成交额、退款额、销量榜。这些不该让任何一个登录用户看到：
 * 竞品用一个学员账号就能摸清平台的营收规模。</p>
 *
 * <p>⚠️ <b>{@code @Lazy} 是这个类能不能启动的关键</b>（不是装饰）：
 * {@link WebMvcConfig} 是 {@code WebMvcConfigurer}，Spring 初始化 MVC 时就会创建它，
 * 进而强制初始化本拦截器；而本拦截器依赖 {@code UserClient}（Feign FactoryBean）。
 * 不延迟就形成「MVC 初始化 → 拦截器 → Feign 客户端 → 需要 MVC 基础设施」
 * 的循环依赖，启动直接失败。</p>
 *
 * <p>📌 <b>实现提示</b>：本类现在在 fm-learning / fm-trade / fm-message / fm-data
 * 各有一份（逻辑完全相同）。第四份副本说明它确实该被提取到
 * {@code fm-auth-resource-sdk} 里，作为"管理端鉴权"的公共能力。
 * 之所以还没动：改公共 SDK 会牵动已验收的 12 个服务，
 * 属于独立的小重构，不适合混在业务阶段里做。</p>
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
            log.warn("非管理员访问数据管理端接口：userId={}, userType={}, uri={}",
                    userId, userType, request.getRequestURI());
            throw new ForbiddenException("无权访问管理端接口");
        }
        return true;
    }
}
