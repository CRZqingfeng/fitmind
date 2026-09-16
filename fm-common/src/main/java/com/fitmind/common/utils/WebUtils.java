package com.fitmind.common.utils;

import com.fitmind.common.constants.Constant;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Web 相关工具：获取当前请求/响应、读取请求头、写 Cookie、取客户端 IP。
 *
 * <p>基于 {@link RequestContextHolder}，无需在方法签名里层层传递
 * {@code HttpServletRequest}。</p>
 */
public class WebUtils {

    private WebUtils() {
    }

    /** 获取当前请求对象，可能为 null（非 Web 线程） */
    public static HttpServletRequest getRequest() {
        ServletRequestAttributes attributes = getRequestAttributes();
        return attributes == null ? null : attributes.getRequest();
    }

    /** 获取当前响应对象，可能为 null */
    public static HttpServletResponse getResponse() {
        ServletRequestAttributes attributes = getRequestAttributes();
        return attributes == null ? null : attributes.getResponse();
    }

    private static ServletRequestAttributes getRequestAttributes() {
        return (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    }

    /** 读取请求头 */
    public static String getHeader(String name) {
        HttpServletRequest request = getRequest();
        return request == null ? null : request.getHeader(name);
    }

    /**
     * 当前请求是否来自网关（即外部请求）。
     *
     * <p>网关转发时会打上 {@code request-from: gateway} 头。用于决定响应是否需要
     * 包装成统一响应 {@code R}——外部请求要包装，服务间 Feign 调用不能包装。</p>
     */
    public static boolean isGatewayRequest() {
        return Constant.GATEWAY_ORIGIN_NAME.equals(getHeader(Constant.REQUEST_FROM_HEADER));
    }

    /**
     * 当前请求是否是服务间内部调用（Feign）。
     */
    public static boolean isFeignRequest() {
        return Constant.FEIGN_ORIGIN_NAME.equals(getHeader(Constant.REQUEST_FROM_HEADER));
    }

    /** 写 Cookie（支持 HttpOnly，用于存放 refreshToken） */
    public static void writeCookie(String name, String value, int maxAge, boolean httpOnly) {
        HttpServletResponse response = getResponse();
        if (response == null) {
            return;
        }
        Cookie cookie = new Cookie(name, value);
        cookie.setPath("/");
        cookie.setMaxAge(maxAge);
        cookie.setHttpOnly(httpOnly);
        response.addCookie(cookie);
    }

    /** 获取客户端 IP */
    public static String getClientIp() {
        HttpServletRequest request = getRequest();
        if (request == null) {
            return null;
        }
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            // 多级代理时取第一个
            int idx = ip.indexOf(',');
            return idx > 0 ? ip.substring(0, idx).trim() : ip.trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }
        return Optional.ofNullable(request.getRemoteAddr()).orElse(null);
    }
}
