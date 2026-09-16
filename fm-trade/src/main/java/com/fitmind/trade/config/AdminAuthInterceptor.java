package com.fitmind.trade.config;

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
 * <p><b>★ 为什么退款审批必须有这一层</b>：审批退款是"把钱退出去"的动作。
 * 光靠"登录了就能调"是极其危险的 —— 任何一个学员都能把自己的退款申请
 * 改成"同意"，或者拒绝别人的退款。常见实现的管理端接口只受<b>登录</b>保护，
 * 任何登录用户都能调（这是它的一类共性问题，不只退款）。</p>
 *
 * <p>这一层的实现与 fm-learning 的 {@code AdminAuthInterceptor} 完全一致 ——
 * 同一个问题用同一种解法，不为了"看起来不一样"而另写一套。</p>
 *
 * <p>⚠️ <b>{@code @Lazy} 不是装饰，是这个类能不能启动的关键</b>：
 * {@link WebMvcConfig} 是 {@code WebMvcConfigurer}，Spring 初始化 MVC 时就会创建它，
 * 进而强制初始化本拦截器；而本拦截器依赖 {@code UserClient}（Feign 的 FactoryBean，
 * 需要完整的应用上下文）。于是形成
 * 「MVC 初始化 → 拦截器 → Feign 客户端 → 需要 MVC 基础设施」的循环依赖，
 * 启动直接失败。加 {@code @Lazy} 后注入的是代理，真正调用时才初始化。
 * （这条经验 M1 就踩过，记在项目记忆里。）</p>
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
            log.warn("非管理员访问交易管理端接口：userId={}, userType={}, uri={}",
                    userId, userType, request.getRequestURI());
            throw new ForbiddenException("无权访问管理端接口");
        }
        return true;
    }
}
