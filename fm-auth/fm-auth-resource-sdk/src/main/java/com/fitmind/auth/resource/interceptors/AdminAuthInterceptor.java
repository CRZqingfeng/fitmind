package com.fitmind.auth.resource.interceptors;

import com.fitmind.api.client.user.UserClient;
import com.fitmind.common.exceptions.ForbiddenException;
import com.fitmind.common.exceptions.UnauthorizedException;
import com.fitmind.common.utils.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Locale;

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
 * <p>★★ <b>但也存在"路径形状相同、权限要求不同"的接口，这时路径前缀不够用</b>，
 * 所以 {@code pathPatterns} 里允许写 <b>{@code METHOD:/path}</b>：</p>
 * <pre>
 * fm.auth.resource.admin.pathPatterns:
 *   - /medias                  # 这个路径下所有方法都要管理员
 *   - DELETE:/medias/*         # ★ 只有 DELETE 要管理员，POST/PUT 不受影响
 * </pre>
 * <p>真实例子：{@code POST /medias/upload-signature}（学员要上传动作考核视频）与
 * {@code DELETE /medias/{id}}（只有运营能删媒资）<b>路径形状完全一样</b>，
 * 而 Ant 风格的路径匹配<b>区分不了 HTTP 方法</b> —— 写 {@code /medias/*} 会把学员的上传也拦掉，
 * 而不写又会把删除放开。这类"要么误伤、要么漏放"的取舍，只能用方法级匹配解开。</p>
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

    /** 配置未指定时的默认管理端路径 */
    private static final String DEFAULT_ADMIN_PATH = "/admin/**";

    /**
     * ★ 用 Ant 匹配而不是 Spring MVC 注册时那套：
     * 注册只负责「让拦截器有机会被调用」，真正决定"要不要管理员"的是下面 {@link #requiresAdmin}，
     * 因为注册用的 {@code addPathPatterns} <b>无法表达 HTTP 方法</b>。
     */
    private static final PathMatcher PATH_MATCHER = new AntPathMatcher();

    private final UserClient userClient;

    /**
     * 需要管理员身份的「路径」或「方法:路径」清单（如 {@code /admin/**}、{@code DELETE:/medias/*}）。
     * 由 {@code ResourceInterceptorConfiguration} 从配置注入。
     */
    private final List<String> patterns;

    public AdminAuthInterceptor(UserClient userClient, List<String> pathPatterns) {
        this.userClient = userClient;
        this.patterns = (pathPatterns == null || pathPatterns.isEmpty())
                ? List.of(DEFAULT_ADMIN_PATH)
                : List.copyOf(pathPatterns);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // ★ 第一件事：这个 (路径, 方法) 组合到底算不算"管理端"。
        //   因为注册阶段无法表达方法，拦截器可能被"多调用"（比如 POST /medias/upload-signature
        //   落在 /medias/* 的注册范围内）—— 这时必须在这里放行，而不是一律要求管理员。
        if (!requiresAdmin(request)) {
            return true;
        }

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

    /**
     * 判断这个请求是否需要管理员身份。
     *
     * <p>两种写法：<br>
     * · {@code /medias/**} —— 该路径下**所有方法**都要管理员；<br>
     * · {@code DELETE:/medias/*} —— **只有 DELETE** 要管理员。</p>
     *
     * <p>★ 方法名大小写不敏感（Spring 的 {@code getMethod()} 是大写，但配置是人手写的）。</p>
     */
    private boolean requiresAdmin(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();
        for (String pattern : patterns) {
            int idx = pattern.indexOf(':');
            if (idx > 0) {
                // 形如 "DELETE:/medias/*"
                String wantMethod = pattern.substring(0, idx).trim().toUpperCase(Locale.ROOT);
                String path = pattern.substring(idx + 1).trim();
                if (wantMethod.equals(method) && PATH_MATCHER.match(path, uri)) {
                    return true;
                }
            } else if (PATH_MATCHER.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 供配置类在注册拦截器时使用：把 {@code METHOD:/path} 里的**路径部分**取出来。
     *
     * <p>⚠️ 这一步不能省：{@code addPathPatterns} 只认路径，
     * 若把 {@code DELETE:/medias/*} 原样传进去，Spring 会把它当成一个普通路径
     * （永远匹配不上）→ <b>拦截器根本不会被调用 → 配置静默失效</b>。</p>
     */
    public static String pathOnly(String pattern) {
        int idx = pattern.indexOf(':');
        return idx > 0 ? pattern.substring(idx + 1).trim() : pattern;
    }
}
