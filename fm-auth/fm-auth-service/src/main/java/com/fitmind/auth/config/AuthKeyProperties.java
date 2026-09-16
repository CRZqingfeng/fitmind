package com.fitmind.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 密钥库配置。
 *
 * <p>对应 application.yml：</p>
 * <pre>
 * fm:
 *   encrypt:
 *     key-store:
 *       alias: fitmind
 *       location: classpath:fitmind.jks
 *       password: fm123321
 *       secret: fm123321
 *       type: JKS
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "fm.encrypt.key-store")
public class AuthKeyProperties {

    /** 密钥别名 */
    private String alias = "fitmind";

    /** 密钥库位置 */
    private String location = "classpath:fitmind.jks";

    /** 密钥库密码 */
    private String password;

    /** 私钥密码 */
    private String secret;

    /** 密钥库类型 */
    private String type = "JKS";
}
