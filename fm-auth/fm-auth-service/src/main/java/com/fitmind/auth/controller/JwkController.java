package com.fitmind.auth.controller;

import cn.hutool.core.codec.Base64;
import com.fitmind.common.annotations.NoWrapper;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.KeyPair;

/**
 * 公钥分发接口。
 *
 * <p>网关启动时会请求本接口拿到公钥（Base64 编码），用于校验用户 token 的签名。
 * 因为只暴露<b>公钥</b>，私钥始终留在认证服务内，所以即使被任意访问也不影响安全。</p>
 *
 * <p>该接口被网关<b>直连</b>调用（不经过网关路由），因此不需要鉴权，
 * 也标了 {@code @NoWrapper} 保证返回纯文本而非统一响应结构。</p>
 */
@Hidden
@RestController
@RequestMapping("/jwks")
@RequiredArgsConstructor
public class JwkController {

    private final KeyPair keyPair;

    @GetMapping
    @NoWrapper
    public String getJwk() {
        return Base64.encode(keyPair.getPublic().getEncoded());
    }
}
