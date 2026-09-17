package com.fitmind.aigc.config;

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
 * <p><b>★ 为什么 AI 模块也需要它</b>：知识库重建接口是<b>运维级操作</b> ——
 * 它会删掉整个向量索引再重建（几百次 embedding 调用）、重建期间检索不可用。
 * 让任何一个登录用户点一下就能重建，等于给了一个"低成本让 AI 变傻"的按钮。</p>
 *
 * <p>⚠️ <b>{@code @Lazy} 是这个类能不能启动的关键</b>（不是装饰）：
 * {@link WebMvcConfig} 是 {@code WebMvcConfigurer}，Spring 初始化 MVC 时就会创建它，
 * 进而强制初始化本拦截器；而本拦截器依赖 {@code UserClient}（Feign FactoryBean）。
 * 不延迟就形成「MVC 初始化 → 拦截器 → Feign 客户端 → 需要 MVC 基础设施」
 * 的循环依赖，启动直接失败。</p>
 *
 * <p>📌 <b>技术债（第五份副本，比前四份更该处理了）</b>：
 * 本类现在在 fm-learning / fm-trade / fm-message / fm-data / fm-aigc 各有一份，
 * 逻辑完全相同。它确实应该被提取到 {@code fm-auth-resource-sdk} 里
 * 作为"管理端鉴权"的公共能力。之所以还没动：改公共 SDK 会牵动
 * 已验收的 12 个服务，属于独立的小重构，不适合混在业务阶段里做。
 * <b>五份副本已经越过"可以再等等"的临界点，建议单独立一个重构任务。</b></p>
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
            log.warn("非管理员访问 AI 管理端接口：userId={}, userType={}, uri={}",
                    userId, userType, request.getRequestURI());
            throw new ForbiddenException("无权访问管理端接口");
        }
        return true;
    }
}
