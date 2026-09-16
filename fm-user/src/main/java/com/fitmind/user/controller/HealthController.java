package com.fitmind.user.controller;

import com.fitmind.common.domain.R;
import com.fitmind.common.utils.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查接口。
 *
 * <p>M0 阶段用于验证链路连通性：<br>
 * 直连：{@code GET http://localhost:8082/health}<br>
 * 走网关：{@code GET http://localhost:10010/us/health}</p>
 *
 * <p>同时演示 {@link UserContext} 的用法——M1 接入鉴权后，
 * 这里能直接读到网关透传的用户 ID。</p>
 */
@Slf4j
@RestController
public class HealthController {

    @Value("${spring.application.name:fm-user}")
    private String applicationName;

    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", applicationName);
        data.put("status", "UP");
        data.put("currentUser", UserContext.getUser());
        data.put("timestamp", LocalDateTime.now().toString());
        log.info("健康检查被调用，service={}", applicationName);
        return R.ok(data);
    }
}
