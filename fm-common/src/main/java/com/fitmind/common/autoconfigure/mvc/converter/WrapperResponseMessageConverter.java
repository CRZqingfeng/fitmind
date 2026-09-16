package com.fitmind.common.autoconfigure.mvc.converter;

import com.fitmind.common.utils.WebUtils;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.lang.NonNull;

import java.io.IOException;
import java.util.List;

/**
 * 统一响应消息转换器。
 *
 * <p><b>它解决什么问题？</b></p>
 *
 * <p>当 Controller 方法声明返回 {@code String} 时（比如登录接口返回 token），
 * Spring MVC 会为它选择 {@link org.springframework.http.converter.StringHttpMessageConverter}。
 * 而此时 {@code WrapperResponseBodyAdvice} 已经把返回值包装成了 {@code R}，
 * 于是 String 转换器拿到一个 R 对象，抛出：</p>
 *
 * <pre>
 * ClassCastException: class com.fitmind.common.domain.R cannot be cast to class java.lang.String
 * </pre>
 *
 * <p><b>怎么解决？</b> 注册一个「能写任意对象」的转换器并放在转换器链最前面。
 * 对来自网关的请求，它把结果交给 Jackson 序列化成 JSON；其余情况一律放弃处理，
 * 交还给后面的默认转换器。</p>
 *
 * <p>注意：它只负责<b>写</b>（响应），不参与读（请求），因此 {@link #canRead} 恒为 false。
 * 它是只读转换器的"半成品"，把请求体解析完全交给默认转换器。</p>
 */
public class WrapperResponseMessageConverter implements HttpMessageConverter<Object> {

    /** 真正干活的转换器：Jackson */
    private final MappingJackson2HttpMessageConverter delegate;

    public WrapperResponseMessageConverter(MappingJackson2HttpMessageConverter delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean canRead(@NonNull Class<?> clazz, MediaType mediaType) {
        // 只处理响应，不处理请求
        return false;
    }

    @Override
    public boolean canWrite(@NonNull Class<?> clazz, MediaType mediaType) {
        // 仅接管「来自网关的外部请求」的响应写出；
        // 服务间 Feign 调用不走这里，保持原始对象序列化
        return WebUtils.isGatewayRequest() && delegate.canWrite(clazz, mediaType);
    }

    @Override
    @NonNull
    public List<MediaType> getSupportedMediaTypes() {
        return delegate.getSupportedMediaTypes();
    }

    @Override
    @NonNull
    public Object read(@NonNull Class<?> clazz, @NonNull HttpInputMessage inputMessage)
            throws IOException, HttpMessageNotReadableException {
        return delegate.read(clazz, inputMessage);
    }

    @Override
    public void write(@NonNull Object o, MediaType contentType, @NonNull HttpOutputMessage outputMessage)
            throws IOException, HttpMessageNotWritableException {
        delegate.write(o, contentType, outputMessage);
    }
}
