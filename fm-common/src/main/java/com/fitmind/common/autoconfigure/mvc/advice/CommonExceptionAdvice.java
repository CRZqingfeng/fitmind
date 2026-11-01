package com.fitmind.common.autoconfigure.mvc.advice;

import com.fitmind.common.domain.R;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.exceptions.BadRequestException;
import com.fitmind.common.exceptions.ForbiddenException;
import com.fitmind.common.exceptions.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDate;
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
     * ★ 请求"格式"不对 —— 这一类以前全部落到兜底的 500「系统繁忙」（《待修复问题清单》#A28）。
     *
     * <p><b>为什么要单独处理</b>：它们都是**客户端**的错，不是服务端挂了。混进 500 有三个实际后果：</p>
     * <ol>
     *   <li>用户改一下地址栏（比如把 `?date=2026-10-31` 改成 `?date=abc`）就能触发，
     *       前端只显示"系统繁忙"，把"我参数错了"误报成"服务挂了"，排查方向直接跑偏；</li>
     *   <li>每次触发都走 {@code log.error("系统异常", e)} 打一整坨堆栈，真实故障会被淹；</li>
     *   <li>HTTP 状态也是错的（错误响应被包装成 **HTTP 200**），监控上看不出这是客户端问题。</li>
     * </ol>
     *
     * <p>★ 与业务错误的区别：业务校验失败仍按项目惯例返回「HTTP 200 + code 400」
     * （见 {@code BizIllegalException}）；而这几个是<b>请求还没进业务逻辑</b>就废了，
     * 所以这里给真正的 4xx 状态码，与 {@code UnauthorizedException / ForbiddenException} 一致。</p>
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String name = e.getName();
        String msg = "参数「" + name + "」格式不对，检查一下再试";
        // 日期类参数单独提示：这是前端最容易拼错的一类（也最容易让用户自己改出来）
        if (e.getRequiredType() != null && LocalDate.class.isAssignableFrom(e.getRequiredType())) {
            msg = "日期参数「" + name + "」格式不对，应为 yyyy-MM-dd（例如 2026-10-31）";
        }
        log.warn("参数类型不符：name={}, value={}, 期望类型={}", name, e.getValue(),
                e.getRequiredType() == null ? "?" : e.getRequiredType().getSimpleName());
        return R.error(400, msg);
    }

    /**
     * 请求体读不出来：JSON 语法错、字段类型不符、body 为空、Content-Type 不对。
     *
     * <p>★ 不复述原始异常信息：Jackson 的报错里带类名与字段路径，对用户没意义，
     * 但**日志里要留全**（出问题时得知道是哪个字段炸的）。</p>
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体无法解析：{}", e.getMessage());
        return R.error(400, "请求内容格式不对（可能是少了字段、类型不符，或内容为空）");
    }

    /**
     * 缺少必填的查询参数（如 `?date=` 整个没传）
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数：{}", e.getParameterName());
        return R.error(400, "缺少参数「" + e.getParameterName() + "」");
    }

    /**
     * 请求方法不支持（如对只能 GET 的接口发 PATCH）
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public R<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持：{}", e.getMethod());
        return R.error(405, "这个地址不支持 " + e.getMethod() + " 请求");
    }

    /**
     * 路径不存在（Spring 6.1 抛 {@code NoResourceFoundException}）。
     *
     * <p>★ 不处理的话它也会变成「HTTP 200 + code 500」—— 一个拼错的地址看起来像"服务炸了"。</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> handleNoResource(NoResourceFoundException e) {
        log.warn("路径不存在：{}", e.getResourcePath());
        return R.error(404, "接口不存在，检查一下地址（" + e.getResourcePath() + "）");
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
