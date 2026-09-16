package com.fitmind.auth.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;

/**
 * 认证服务核心配置：从密钥库加载 RSA 密钥对。
 *
 * <p>用标准 JDK 的 {@link KeyStore} API 加载，不依赖额外三方库，
 * 加载过程完全透明可控。</p>
 *
 * <p>密钥对用途：<b>私钥签名 token，公钥对外暴露供网关验签</b>。</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AuthKeyProperties.class)
public class AuthConfig {

    @Bean
    public KeyPair keyPair(AuthKeyProperties properties) throws Exception {
        String location = properties.getLocation();
        // 1.加载密钥库
        KeyStore keyStore = KeyStore.getInstance(properties.getType());
        String path = location.startsWith("classpath:") ? location.substring("classpath:".length()) : location;
        try (InputStream is = new ClassPathResource(path).getInputStream()) {
            keyStore.load(is, properties.getPassword().toCharArray());
        }
        // 2.取私钥
        PrivateKey privateKey = (PrivateKey) keyStore.getKey(
                properties.getAlias(), properties.getSecret().toCharArray());
        // 3.取公钥（来自自签证书）
        Certificate certificate = keyStore.getCertificate(properties.getAlias());
        if (privateKey == null || certificate == null) {
            throw new IllegalStateException("密钥库中未找到别名对应的密钥对：" + properties.getAlias());
        }
        PublicKey publicKey = certificate.getPublicKey();
        log.info("RSA 密钥对加载成功，alias={}，算法={}", properties.getAlias(), privateKey.getAlgorithm());
        return new KeyPair(publicKey, privateKey);
    }
}
