package com.fitmind.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 认证服务启动类。
 *
 * <p>负责登录、签发与刷新 token，并通过 {@code GET /jwks} 对外提供验签公钥。</p>
 */
//跨包扫描（因为依赖了 fm-auth-common、fm-auth-resource-sdk、fm-api 等）
@SpringBootApplication(scanBasePackages = "com.fitmind")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.fitmind.api.client")
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
