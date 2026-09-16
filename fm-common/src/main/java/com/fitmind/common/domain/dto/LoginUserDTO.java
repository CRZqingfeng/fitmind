package com.fitmind.common.domain.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 登录用户信息（JWT 载荷载体）。
 *
 * <p>网关解析 token 后得到本对象，再把 {@code userId} 通过请求头
 * {@code user-info} 透传给下游微服务。</p>
 */
@Data
public class LoginUserDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 id */
    private Long userId;

    /** 角色 id（RBAC 用，M1 阶段学员固定为 0） */
    private Long roleId;

    /** 记住我 */
    private Boolean rememberMe;
}
