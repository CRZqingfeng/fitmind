package com.fitmind.gateway;

import com.fitmind.gateway.config.AuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 网关启动类。
 *
 * <p>统一入口：负责路由转发、鉴权、限流与访问日志。
 * 所有外部请求都先经过网关，再由网关转发到对应的微服务。</p>
 *
 * <p><b>注意</b>：网关是 WebFlux 技术栈，因此<b>不要</b>把
 * {@code scanBasePackages} 设为 {@code com.fitmind}——那会把 fm-common 里
 * 基于 Servlet 的组件（全局异常处理器、响应包装器）也扫进来，导致启动失败。
 * 网关只扫描自己的包。</p>
 */
@SpringBootApplication
@EnableDiscoveryClient
@ConfigurationPropertiesScan("com.fitmind.gateway.config")
@EnableConfigurationProperties(AuthProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
