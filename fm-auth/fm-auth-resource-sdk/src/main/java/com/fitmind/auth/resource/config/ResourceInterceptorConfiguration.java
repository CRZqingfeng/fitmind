package com.fitmind.auth.resource.config;

import com.fitmind.auth.resource.interceptors.FeignRelayUserInterceptor;
import com.fitmind.auth.resource.interceptors.LoginAuthInterceptor;
import com.fitmind.auth.resource.interceptors.UserInfoInterceptor;
import feign.RequestInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
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
 *     <li>{@link FeignRelayUserInterceptor} —— 服务间调用时继续透传用户信息。</li>
 * </ol>
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(ResourceAuthProperties.class)
public class ResourceInterceptorConfiguration implements WebMvcConfigurer {

    private final ResourceAuthProperties authProperties;

    public ResourceInterceptorConfiguration(ResourceAuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1.用户信息拦截器：始终注册，负责把请求头里的用户 id 放进 UserContext
        registry.addInterceptor(new UserInfoInterceptor()).order(0);

        // 2.登录拦截器：由配置开关控制
        if (Boolean.FALSE.equals(authProperties.getEnable())) {
            log.info("资源服务登录拦截已关闭（fm.auth.resource.enable=false）");
            return;
        }
        var registration = registry.addInterceptor(new LoginAuthInterceptor()).order(1);

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
