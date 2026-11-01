package com.fitmind.aigc.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 提交订单结果（fm-trade {@code POST /orders} 的响应）。
 *
 * <p>与 {@link PrePlaceOrderDTO} 同样是"消费方自己声明"的镜像 DTO。
 * 字段名必须与 {@code fm-trade.domain.vo.PlaceOrderResultVO} 保持一致
 * —— 这是 JSON 契约，编译期检查不到。</p>
 *
 * <p>★★ {@code code} / {@code msg} 这两个字段<b>不是业务字段，是"失败信封"的识别位</b>：</p>
 *
 * <p>fm-trade 的业务异常（已购 / 有未支付旧单 / 下架…）由
 * {@code CommonExceptionAdvice} 包成 {@code R{code,msg}} 返回，
 * 而且<b>没有 {@code @ResponseStatus}</b> → <b>HTTP 仍然是 200</b>。
 * 同时 {@code WrapperResponseBodyAdvice} 只在"来自网关"的请求上包装，
 * 内部 Feign 调用拿到的是裸对象 —— <b>两条路径长得一模一样</b>，
 * 只看 HTTP 状态码根本区分不出来。</p>
 *
 * <p>所以判据是：<b>{@code code == null} → 裸 VO（成功）；{@code code != 200} → 业务失败信封</b>。
 * 详见 {@link PrePlaceOrderDTO} 里更完整的说明。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlaceOrderResultDTO {

    /** 订单id */
    private Long orderId;

    /** 实付金额（分）—— 由 fm-trade 从库里算 */
    private Integer realAmount;

    /** 是不是"这次真的新建了一笔单"（false = 命中了已存在的订单，属**幂等成功**，不是失败） */
    private Boolean created;

    /**
     * 支付截止时间。
     *
     * <p>★ 声明成 {@code String} 而不是 {@code LocalDateTime}：服务方序列化
     * {@code LocalDateTime} 的形态取决于它的 Jackson 配置（数组 / ISO 串都可能），
     * 而这里只需要"原样透传给用户看" —— <b>当一个值只是用来展示时，
     * 用最不容易反序列化失败的形状接它</b>（{@link PrePlaceOrderDTO#getPayOutTime()}
     * 也是同一个理由）。</p>
     */
    private String payOutTime;

    // ==================== ★ 业务失败信封的识别位（不是业务数据）====================

    /** 被调方把业务失败装进 HTTP 200 时的状态码；**裸 VO 成功时为 null** */
    private Integer code;

    /** 失败原因（人话），失败时才有 */
    private String msg;
}
