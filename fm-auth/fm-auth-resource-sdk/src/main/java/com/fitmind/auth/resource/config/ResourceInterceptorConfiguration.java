package com.fitmind.auth.resource.config;

import com.fitmind.api.client.user.UserClient;
import com.fitmind.auth.resource.interceptors.AdminAuthInterceptor;
import com.fitmind.auth.resource.interceptors.FeignRelayUserInterceptor;
import com.fitmind.auth.resource.interceptors.LoginAuthInterceptor;
import com.fitmind.auth.resource.interceptors.UserInfoInterceptor;
import feign.RequestInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 资源服务鉴权自动装配。
 *
 * <p>业务服务只要引入本 SDK，拦截器会自动注册，实现「零代码接入鉴权」：</p>
 * <ol>
 *     <li>{@link UserInfoInterceptor}（order 0）—— 把网关透传的用户 id 放入 UserContext；</li>
 *     <li>{@link LoginAuthInterceptor}（order 1）—— 需要登录的路径做强制登录校验；</li>
 *     <li>{@link AdminAuthInterceptor}（order 2，<b>可选</b>）—— {@code /admin/**} 要求管理员；</li>
 *     <li>{@link FeignRelayUserInterceptor} —— 服务间调用时继续透传用户信息。</li>
 * </ol>
 *
 * <p>★ <b>这三个拦截器的 order 是一份「不能各自为政」的约定</b>，所以它们现在写在同一个类里
 * （{@link #USER_INFO_ORDER} / {@link #LOGIN_ORDER} / {@link #ADMIN_ORDER}）。
 * 在这之前，管理端拦截器在 5 个服务里各有一份副本，而
 * {@code registry.addInterceptor(x)} <b>不写 order 时默认值是 0</b> ——
 * 与 {@link UserInfoInterceptor}（也是 0）撞号，执行顺序变成未定义的。
 * 后果是「管理员带着有效 token 访问 {@code /admin/**} 却报未登录」（因为
 * {@code UserContext} 还没被填充），而<b>症状与根因看起来毫无关系</b>。
 * 5 份副本里有 4 份的注释都写着「order 必须是 2」，仍然被踩了第二次 ——
 * <b>靠"记得照抄"维持的约定不算约定，写在一处、由代码保证才算。</b></p>
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(ResourceAuthProperties.class)
public class ResourceInterceptorConfiguration implements WebMvcConfigurer {

    /** 用户信息拦截器：必须在最前，它负责把用户 id 放进 UserContext */
    public static final int USER_INFO_ORDER = 0;
    /** 登录拦截器：依赖 UserContext，必须在 USER_INFO_ORDER 之后 */
    public static final int LOGIN_ORDER = 1;
    /** 管理端拦截器：依赖 UserContext 且要在登录校验之后，必须大于前两者 */
    public static final int ADMIN_ORDER = 2;

    /** 管理端接口的路径约定（配置未指定时用它） */
    private static final String DEFAULT_ADMIN_PATH = "/admin/**";

    private final ResourceAuthProperties authProperties;

    /**
     * ⚠️ 这里用的是 {@link ObjectProvider} 而不是直接注入 {@link AdminAuthInterceptor}：
     * 管理端拦截器是<b>可选</b>的（默认不装配），直接注入在开关关闭时会启动失败。
     */
    private final ObjectProvider<AdminAuthInterceptor> adminAuthInterceptorProvider;

    public ResourceInterceptorConfiguration(ResourceAuthProperties authProperties,
                                            ObjectProvider<AdminAuthInterceptor> adminAuthInterceptorProvider) {
        this.authProperties = authProperties;
        this.adminAuthInterceptorProvider = adminAuthInterceptorProvider;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1.用户信息拦截器：始终注册，负责把请求头里的用户 id 放进 UserContext
        registry.addInterceptor(new UserInfoInterceptor()).order(USER_INFO_ORDER);

        // 2.登录拦截器：由配置开关控制
        if (Boolean.FALSE.equals(authProperties.getEnable())) {
            log.info("资源服务登录拦截已关闭（fm.auth.resource.enable=false）");
            return;
        }
        var registration = registry.addInterceptor(new LoginAuthInterceptor()).order(LOGIN_ORDER);

        // 2.1 需要登录的路径
        List<String> includePaths = authProperties.getIncludeLoginPaths();
        if (!CollectionUtils.isEmpty(includePaths)) {
            registration.addPathPatterns(includePaths);
        }
        // 2.2 免登录白名单
        List<String> excludePaths = authProperties.getExcludeLoginPaths();
        if (!CollectionUtils.isEmpty(excludePaths)) {
            registration.excludePathPatterns(excludePaths);
        }
        // 2.3 放行接口文档与健康检查
        registration.excludePathPatterns(
                "/v2/**", "/v3/**", "/swagger-resources/**", "/webjars/**", "/doc.html",
                "/actuator/**", "/error", "/jwks"
        );

        // 3.管理端权限拦截器：可选（fm.auth.resource.admin.enabled=true）
        //   注意「登录」与「管理员」是两道不同的关卡：前者问"你是谁"，后者问"你配不配"。
        //   只做前者的话，任何学员都能调管理端接口。
        AdminAuthInterceptor adminAuthInterceptor = adminAuthInterceptorProvider.getIfAvailable();
        if (adminAuthInterceptor == null) {
            return;
        }
        List<String> adminPaths = authProperties.getAdmin().getPathPatterns();
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns(CollectionUtils.isEmpty(adminPaths)
                        ? List.of(DEFAULT_ADMIN_PATH) : adminPaths)
                .order(ADMIN_ORDER);
        log.info("管理端鉴权拦截器已注册：路径={}，order={}",
                CollectionUtils.isEmpty(adminPaths) ? List.of(DEFAULT_ADMIN_PATH) : adminPaths, ADMIN_ORDER);
    }

    /**
     * 管理端鉴权拦截器：仅在 {@code fm.auth.resource.admin.enabled=true} 时装配。
     *
     * <p>⚠️ 参数上的 {@link Lazy} <b>不是装饰，而是启动成败的关键</b>：
     * 本配置类是 {@code WebMvcConfigurer}，Spring 初始化 MVC 时就会创建它，
     * 进而强制初始化这个拦截器；而拦截器依赖 {@code UserClient}（Feign 的 FactoryBean，
     * 需要完整的应用上下文）。不加 {@code @Lazy} 就会形成
     * 「MVC 初始化 → 拦截器 → Feign 客户端 → 需要 MVC 基础设施」的循环依赖，启动直接失败。
     * 加了之后注入的是代理，真正调用（第一次请求）时才初始化。</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "fm.auth.resource.admin", name = "enabled", havingValue = "true")
    public AdminAuthInterceptor adminAuthInterceptor(@Lazy UserClient userClient) {
        return new AdminAuthInterceptor(userClient);
    }

    /**
     * Feign 用户信息透传：仅当 classpath 中存在 Feign 时装配
     */
    @Bean
    @ConditionalOnClass(RequestInterceptor.class)
    @ConditionalOnMissingBean(name = "feignRelayUserInterceptor")
    public FeignRelayUserInterceptor feignRelayUserInterceptor() {
        return new FeignRelayUserInterceptor();
    }
}
