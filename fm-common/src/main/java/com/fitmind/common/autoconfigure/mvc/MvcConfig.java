package com.fitmind.common.autoconfigure.mvc;

import com.fitmind.common.autoconfigure.mvc.converter.WrapperResponseMessageConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * MVC 增强配置。
 *
 * <p>核心作用：把 {@link WrapperResponseMessageConverter} 插到消息转换器链的最前面。</p>
 *
 * <p><b>为什么必须显式插到最前面？</b> Spring MVC 选转换器是「按顺序取第一个 canWrite 为 true 的」。
 * 若自定义转换器排在 {@code StringHttpMessageConverter} 之后，那么返回 String 的接口
 * （如登录返回 token）仍会被 String 转换器处理，而此时响应体已经被包装成了 {@code R}，
 * 从而抛出 {@code ClassCastException}。</p>
 *
 * <p>本配置标注了 {@code @ConditionalOnMissingClass}：网关是 WebFlux 技术栈，
 * 不存在也不应加载这套 Servlet 版的 MVC 配置。</p>
 */
@Slf4j
@Configuration
@ConditionalOnClass({WebMvcConfigurer.class, MappingJackson2HttpMessageConverter.class})
@ConditionalOnMissingClass("org.springframework.cloud.gateway.filter.GlobalFilter")
public class MvcConfig implements WebMvcConfigurer {

    private final MappingJackson2HttpMessageConverter delegate;

    public MvcConfig(MappingJackson2HttpMessageConverter delegate) {
        this.delegate = delegate;
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        // 插到最前面，确保统一响应包装后的结果由 Jackson 写出
        converters.add(0, new WrapperResponseMessageConverter(delegate));
        log.info("已注册统一响应消息转换器 WrapperResponseMessageConverter（位置：0）");
    }
}
