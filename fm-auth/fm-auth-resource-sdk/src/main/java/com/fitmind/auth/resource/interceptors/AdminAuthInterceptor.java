package com.fitmind.auth.resource.interceptors;

import com.fitmind.api.client.user.UserClient;
import com.fitmind.common.exceptions.ForbiddenException;
import com.fitmind.common.exceptions.UnauthorizedException;
import com.fitmind.common.utils.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端（后台）权限拦截器：要求当前用户是<b>管理员</b>。
 *
 * <p><b>它解决的是什么问题</b>：登录 ≠ 有权。常见实现的管理端接口只受「登录」保护，
 * 于是任何一个学员都能调管理端接口 —— 比如把自己的退款申请改成「同意」、
 * 或者点一下「重建知识库」让 AI 变傻。这是它的一类共性问题，不只在某一个模块上。</p>
 *
 * <p>★ <b>用「路径前缀」表达权限，而不是在每个方法上写角色判断</b>：
 * 前者漏不掉（新增接口只要放在 {@code /admin/**} 下就自动受保护），
 * 后者总会漏（新加的方法忘了加注解，而且不会有任何提示）。
 * 所以本项目的约定是：<b>所有管理端接口统一放 {@code /admin/**} 下</b>。</p>
 *
 * <p>⚠️⚠️ <b>本类是从 5 份副本收敛来的，而收敛前每位作者都要踩一次同一个坑 ——
 * 所以这里把那条规则写死在代码里（见 {@code ResourceInterceptorConfiguration.ADMIN_ORDER}）</b>：
 * 本拦截器必须在 {@link UserInfoInterceptor}（order 0，负责把网关注入的用户 id 放进
 * {@link UserContext}）与 {@link LoginAuthInterceptor}（order 1）<b>之后</b>执行。
 * 顺序搞错的表现是「管理员带着有效 token 访问管理端接口却报未登录」——
 * 症状（权限判断）与根因（拦截器顺序）看起来毫无关系，非常难查。</p>
 *
 * <p>⚠️ <b>依赖 {@code UserClient} 是「Feign 的 FactoryBean」</b>，构造它需要完整的应用上下文；
 * 而本拦截器由 {@code WebMvcConfigurer} 在 MVC 初始化阶段被创建 ——
 * 直接构造注入会形成「MVC 初始化 → 拦截器 → Feign 客户端 → 需要 MVC 基础设施」
 * 的循环依赖，启动直接失败。所以注入点必须加 {@code @Lazy}（注入的是代理，首次调用才初始化）。
 * 见 {@code ResourceInterceptorConfiguration#adminAuthInterceptor}。</p>
 */
@Slf4j
public class AdminAuthInterceptor implements HandlerInterceptor {

    /** fm_user 的用户类型：1-管理员 2-学员 3-教练 */
    private static final int TYPE_ADMIN = 1;

    private final UserClient userClient;

    public AdminAuthInterceptor(UserClient userClient) {
        this.userClient = userClient;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Long userId = UserContext.getUser();
        if (userId == null) {
            // 走到这里通常意味着「UserInfoInterceptor 没生效」或「拦截器顺序错了」，
            // 而不是用户真的没登录 —— 因为未登录会在 LoginAuthInterceptor（order 1）就被拦下。
            // 把这个区别写进日志，能省掉一次「为什么登录着还说未登录」的排查。
            log.warn("管理端接口拿不到用户上下文（前置拦截器可能没生效）：uri={}", request.getRequestURI());
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
