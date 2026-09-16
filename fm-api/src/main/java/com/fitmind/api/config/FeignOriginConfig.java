package com.fitmind.api.config;

import com.fitmind.common.constants.Constant;
import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Feign 调用统一配置。
 *
 * <p>为所有服务间 Feign 请求做两件事：</p>
 * <ol>
 *     <li>打上 {@code request-from: feign} 标记 —— 让被调服务的
 *         {@code WrapperResponseBodyAdvice} 不要包装返回值，
 *         否则 Feign 侧按领域对象反序列化会失败；</li>
 *     <li>透传 {@code requestId} —— 保证一次请求跨多个服务的日志能用同一个 id 串起来。</li>
 * </ol>
 *
 * <p><b>依赖约束</b>：fm-api 是「服务间契约」模块，其配置类引用了 Feign 类型，
 * 因此依赖 fm-api 的服务<b>必须</b>引入 {@code spring-cloud-starter-openfeign}
 * （若还用到 {@code lb://} 服务名解析，还需 {@code spring-cloud-starter-loadbalancer}）。</p>
 */
@Configuration
public class FeignOriginConfig {

    @Bean
    public RequestInterceptor feignOriginInterceptor() {
        return template -> {
            // 1. 标记为服务间内部调用
            template.header(Constant.REQUEST_FROM_HEADER, Constant.FEIGN_ORIGIN_NAME);
            // 2. 透传链路 id
            String requestId = MDC.get(Constant.REQUEST_ID_HEADER);
            if (StringUtils.hasText(requestId)) {
                template.header(Constant.REQUEST_ID_HEADER, requestId);
            }
        };
    }
}
