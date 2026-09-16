package com.fitmind.auth.gateway.config;

import com.fitmind.auth.gateway.util.AuthUtil;
import com.fitmind.auth.gateway.util.JwtSignerHolder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;

/**
 * 网关鉴权自动装配。
 *
 * <p>网关（fm-gateway）引入本 SDK 依赖后，这两个 Bean 会自动就绪，
 * 无需在网关里写任何装配代码。</p>
 */
@AutoConfiguration
public class AuthAutoConfiguration {

    @Bean
    @ConditionalOnClass(DiscoveryClient.class)
    public JwtSignerHolder jwtSignerHolder(DiscoveryClient discoveryClient) {
        JwtSignerHolder holder = new JwtSignerHolder(discoveryClient);
        // 启动后异步加载公钥（失败会自动重试，不阻塞应用启动）
        holder.init();
        return holder;
    }

    @Bean
    public AuthUtil authUtil(JwtSignerHolder jwtSignerHolder) {
        return new AuthUtil(jwtSignerHolder);
    }
}
