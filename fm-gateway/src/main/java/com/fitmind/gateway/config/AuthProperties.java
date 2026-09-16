package com.fitmind.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关鉴权配置。
 *
 * <p>三级判定模型：</p>
 * <ol>
 *     <li>{@code excludePath} —— 完全放行，连 token 都不解析（登录、注册等公开接口）；</li>
 *     <li>{@code requireLoginPath} —— 必须携带有效 token，否则网关直接返回 401；</li>
 *     <li>两者都不匹配 —— 解析 token 但不拦截，由业务服务自行决定。</li>
 * </ol>
 *
 * <pre>
 * fm:
 *   auth:
 *     excludePath:                 # 免鉴权（Ant 风格，支持 METHOD:/path）
 *       - POST:/as/accounts/login
 *     requireLoginPath:            # 需要登录
 *       - /us/**
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "fm.auth")
public class AuthProperties {

    /** 免鉴权路径集合 */
    private List<String> excludePath = new ArrayList<>();

    /** 需要登录才能访问的路径集合 */
    private List<String> requireLoginPath = new ArrayList<>();
}
