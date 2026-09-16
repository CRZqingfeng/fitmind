package com.fitmind.auth.resource.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 资源服务鉴权配置项。
 *
 * <p>配置示例（业务服务的 application.yml）：</p>
 * <pre>
 * fm:
 *   auth:
 *     resource:
 *       enable: true              # 是否强制登录
 *       includeLoginPaths:        # 需要登录的路径
 *         - /users/**
 *       excludeLoginPaths:        # 免登录白名单（优先级高于 include）
 *         - /codes
 *         - /students/register
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "fm.auth.resource")
public class ResourceAuthProperties {

    /** 是否开启登录拦截 */
    private Boolean enable = true;

    /** 需要登录才能访问的路径 */
    private List<String> includeLoginPaths;

    /** 免登录白名单 */
    private List<String> excludeLoginPaths;
}
