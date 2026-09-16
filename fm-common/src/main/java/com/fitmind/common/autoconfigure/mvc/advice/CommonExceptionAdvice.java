package com.fitmind.common.autoconfigure.mvc.advice;

import com.fitmind.common.domain.R;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.exceptions.BadRequestException;
import com.fitmind.common.exceptions.ForbiddenException;
import com.fitmind.common.exceptions.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>把各类异常统一转换为 {@link R} 结构返回，避免异常堆栈直接暴露给前端。
 * 使用方需扫描 {@code com.fitmind} 包使其生效。</p>
 */
@Slf4j
@RestControllerAdvice
public class CommonExceptionAdvice {

    /**
     * 业务异常：业务规则不满足
     */
    @ExceptionHandler(BizIllegalException.class)
    public R<Void> handleBizException(BizIllegalException e) {
        log.warn("业务异常：{}", e.getMessage());
        return R.error(e.getCode(), e.getMessage());
    }

    /**
     * 请求参数非法
     */
    @ExceptionHandler(BadRequestException.class)
    public R<Void> handleBadRequest(BadRequestException e) {
        log.warn("参数异常：{}", e.getMessage());
        return R.error(400, e.getMessage());
    }

    /**
     * 参数校验失败（@Valid 作用于 @RequestBody）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValidException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败：{}", msg);
        return R.error(400, msg);
    }

    /**
     * 参数绑定失败（@Valid 作用于表单 / 查询参数）
     */
    @ExceptionHandler(BindException.class)
    public R<Void> handleBindException(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数绑定失败：{}", msg);
        return R.error(400, msg);
    }

    /**
     * 未认证：未登录 / token 非法 / token 过期。
     *
     * <p>返回真实的 HTTP 401，而不是「HTTP 200 + 业务错误码」。
     * 这样服务间 Feign 调用方能正确识别失败（否则会把错误响应反序列化成一个
     * 字段全 null 的对象，导致难以排查的问题），前端也能走统一的 401 拦截逻辑。</p>
     */
    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public R<Void> handleUnauthorized(UnauthorizedException e) {
        log.warn("未认证：{}", e.getMessage());
        return R.error(e.getCode(), e.getMessage());
    }

    /**
     * 无权限：已登录但角色不允许（HTTP 403）
     */
    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public R<Void> handleForbidden(ForbiddenException e) {
        log.warn("无权限：{}", e.getMessage());
        return R.error(403, e.getMessage());
    }

    /**
     * 兜底异常
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return R.error(500, "系统繁忙，请稍后再试");
    }
}
