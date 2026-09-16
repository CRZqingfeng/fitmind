package com.fitmind.common.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 Controller 方法「不要包装成统一响应 R」。
 *
 * <p>个别接口（如返回纯字符串、二进制流的接口）不需要 {@code R{code,msg,data}} 结构，
 * 在方法上加本注解即可跳过包装。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NoWrapper {
}
