package com.fitmind.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 安全相关配置。
 *
 * <p>只引入 {@code spring-security-crypto} 用于密码加密，
 * <b>不引入 spring-security 全家桶</b>——本项目的认证鉴权是自研的
 * JWT 方案（网关验签 + 服务间透传），引入 Spring Security 反而会带来
 * 过滤器链与自研方案冲突的问题。</p>
 *
 * <p>BCrypt 的特点：自带随机盐、计算慢，能有效抵抗彩虹表与暴力破解。</p>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
