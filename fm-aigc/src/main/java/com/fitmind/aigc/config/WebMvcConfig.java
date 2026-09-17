package com.fitmind.aigc.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 配置：把管理端鉴权挂到 {@code /admin/**} 上。
 *
 * <p>★ 只挂管理端，不挂 {@code /chat/**}：
 * 对话接口的登录校验由 {@code fm-auth-resource-sdk} 统一负责
 * （配置在 {@code fm.auth.resource.includeLoginPaths}）。
 * 两处都校验不会更安全，只会让"为什么这个接口 401"更难排查。</p>
 *
 * <p>⚠️⚠️ {@code order(2)} 是<b>必需的，不是可选的</b>。
 * SDK 里 {@code UserInfoInterceptor}=0（负责把网关注入的用户 id 放进 {@code UserContext}）、
 * {@code LoginAuthInterceptor}=1（负责强制登录）。
 * 而 {@code addInterceptor} <b>不写 order 时的默认值也是 0</b> ——
 * 与 UserInfoInterceptor 撞在一起，执行顺序就变成未定义的。
 * <b>后果</b>：本拦截器可能在 UserContext 还没被填充时执行，
 * 于是把管理员也拦下，报"未登录" ——
 * 而真正的原因（拦截器顺序）和症状（权限判断）看起来毫无关系，非常难查。</p>
 *
 * <p>📌 这是本项目第五份拦截器副本，也第二次踩到这个 order 坑
 * （前四份的注释里都写着"order 必须是 2"）。<b>这就是"注释无法替代约定"的例子。</b></p>
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
