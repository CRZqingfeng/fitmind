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
 *       admin:                    # 管理端（后台）鉴权，默认关闭
 *         enabled: true           # 只有真的有管理端接口的服务才打开
 *         pathPatterns:           # 默认 /admin/**
 *           - /admin/**
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

    /** 管理端（后台）鉴权 */
    private Admin admin = new Admin();

    /**
     * 管理端鉴权配置。
     *
     * <p>★ <b>为什么默认关闭、要显式打开</b>：这个拦截器依赖 {@code UserClient}（Feign），
     * 只有真的写了 {@code /admin/**} 接口的服务才需要它。默认开启意味着所有引了本 SDK 的
     * 服务都要具备「Feign 客户端可注入」这个前提，而那是另一件需要显式配置的事
     * （{@code @EnableFeignClients}）—— 一旦缺了，报错会发生在<b>启动期</b>，
     * 而报错内容（缺 bean）与真实原因（这个服务并不需要管理端鉴权）完全对不上。</p>
     *
     * <p>默认关闭的代价是多一行配置；默认开启的代价是"不需要它的服务莫名启动失败"。
     * 后者更贵。</p>
     */
    @Data
    public static class Admin {

        /** 是否启用管理端鉴权拦截器。默认关：只有真有管理端接口的服务才开 */
        private boolean enabled = false;

        /** 需要管理员身份的路径。约定是所有管理端接口放 /admin/** 下 */
        private List<String> pathPatterns = List.of("/admin/**");
    }
}
