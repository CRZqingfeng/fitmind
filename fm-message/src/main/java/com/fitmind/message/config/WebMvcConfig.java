package com.fitmind.message.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 本服务的 Web MVC 扩展。
 *
 * <p>把 {@link AdminAuthInterceptor} 挂到 {@code /admin/**} 上 ——
 * <b>用路径前缀表达权限</b>，比在每个方法里写一句角色判断更难漏。</p>
 *
 * <p>⚠️ order 必须是 2：fm-auth-resource-sdk 里 {@code UserInfoInterceptor}=0（填 UserContext）、
 * {@code LoginAuthInterceptor}=1（登录校验），本拦截器要在它们之后跑，
 * 否则 UserContext 里还没有用户 id，会被误判成未登录。</p>
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
