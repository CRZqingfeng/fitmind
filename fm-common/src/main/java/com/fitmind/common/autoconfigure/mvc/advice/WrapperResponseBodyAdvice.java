package com.fitmind.common.autoconfigure.mvc.advice;

import com.fitmind.common.annotations.NoWrapper;
import com.fitmind.common.constants.Constant;
import com.fitmind.common.domain.R;
import com.fitmind.common.utils.WebUtils;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 统一响应包装器。
 *
 * <p><b>核心设计</b>：只有「来自网关的外部请求」才把返回值包装成 {@link R}；
 * 服务间 Feign 调用返回原始对象。原因是 Feign 客户端声明的是领域对象
 * （如 {@code UserDTO}）而不是 {@code R<UserDTO>}，一旦被包装就反序列化失败。</p>
 *
 * <p>判断依据：网关转发时打上的 {@code request-from: gateway} 请求头。</p>
 *
 * <p>若某接口确实不需要包装，在方法上加 {@link NoWrapper} 注解即可跳过。</p>
 */
@RestControllerAdvice
@ConditionalOnClass(ResponseBodyAdvice.class)
public class WrapperResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(@NonNull MethodParameter methodParameter,
                            @NonNull Class<? extends HttpMessageConverter<?>> converterType) {
        // 1.标注了 @NoWrapper 的接口不包装
        if (methodParameter.hasMethodAnnotation(NoWrapper.class)) {
            return false;
        }
        // 2.返回值已经是 R 的不重复包装
        if (methodParameter.getParameterType() == R.class) {
            return false;
        }
        // 3.仅包装来自网关的外部请求
        return WebUtils.isGatewayRequest();
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  @NonNull MethodParameter returnType,
                                  @NonNull MediaType selectedContentType,
                                  @NonNull Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  @NonNull ServerHttpRequest request,
                                  @NonNull ServerHttpResponse response) {
        String requestId = MDC.get(Constant.REQUEST_ID_HEADER);
        if (body == null) {
            return R.ok().requestId(requestId);
        }
        if (body instanceof R) {
            return body;
        }
        return R.ok(body).requestId(requestId);
    }
}
