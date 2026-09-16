package com.fitmind.learning.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 本服务的 Web MVC 扩展。
 *
 * <p>只做一件事：把 {@link AdminAuthInterceptor} 挂到 {@code /admin/**} 上。</p>
 *
 * <p>⚠️ order 必须是 2：fm-auth-resource-sdk 里 {@code UserInfoInterceptor}=0（填 UserContext）、
 * {@code LoginAuthInterceptor}=1（登录校验），本拦截器要在它们之后跑，
 * 否则 UserContext 里还没有用户 id。</p>
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/admin/**")
                .order(2);
    }
}
