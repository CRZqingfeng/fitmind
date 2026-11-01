package com.fitmind.aigc.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 预下单结果（fm-trade {@code GET /orders/prePlace} 的响应）。
 *
 * <p>★ <b>这是"消费方自己声明"的 DTO，不是共享契约</b> —— 两点说明：
 * ① 只声明 AI 工具<b>真正用到</b>的字段（服务方新增字段不会影响这里，
 *    因为反序列化默认忽略未知字段）；
 * ② <b>如果哪天要用到新字段，就在这里补一行</b>，而不是让 fm-trade 的 VO
 *    反向依赖 fm-aigc。<b>依赖方向永远是"消费方依赖服务方"。</b></p>
 *
 * <p>它与 {@code fm-trade.domain.vo.PrePlaceOrderVO} 的字段名必须一致 ——
 * 这是 JSON 契约，编译期检查不到，所以验收里专门有一条断言去校准：
 * <b>"工具返回的金额 == 库里算出来的金额"</b>，一旦字段名漂移就会立刻暴露。</p>
 *
 * <p>★★ <b>为什么这里多出 {@code code} / {@code msg} 两个"不属于业务数据"的字段</b>
 * （M6-8 #5）：fm-trade 的业务异常（已购 / 有未支付旧单 / 金额非法…）由
 * {@code CommonExceptionAdvice} 包成 {@code R{code,msg}} 返回，而那个分支
 * <b>没有 {@code @ResponseStatus}</b> → <b>HTTP 状态码仍然是 200</b>。
 * Feign 于是按本 DTO 反序列化这个"成功响应"：
 * 所有业务字段都是 null，<b>而且不抛异常</b>。</p>
 *
 * <p>结果就是工具看到的失败长得和"服务没返回数据"完全一样，
 * 只能对用户说一句没营养的"下单失败，请稍后再试" —— <b>真实的失败原因被丢在了半路</b>。</p>
 *
 * <p>判据：<b>{@code code == null} → 裸 VO（内部调用的正常成功）；{@code code != 200} → 业务失败信封</b>。
 * ★ 注意这跟"HTTP 状态码"是两件事：<b>不能靠 catch 异常来兜</b>，
 * 因为这里压根没有异常可 catch。</p>
 *
 * <p>★ 项目里其实<b>早就有人识别过这个模式</b>：{@code UnauthorizedException} 的分支
 * 就加了 {@code @ResponseStatus(401)}，注释原文写着"否则会把错误响应反序列化成一个
 * 字段全 null 的对象"。只是那个结论没被推广到业务异常上 —— 本字段就是补上这一步。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PrePlaceOrderDTO {

    /** 订单id（提交订单时带回） */
    private Long orderId;

    /** 商品总额（分） */
    private Integer totalAmount;

    /** 优惠金额（分） */
    private Integer discountAmount;

    /** ★ 实付金额（分）—— 由 fm-trade 从库里算，不由模型决定 */
    private Integer realAmount;

    /** 支付截止时间（超过会被超时任务自动关闭） */
    private String payOutTime;

    private List<Item> courses;

    // ==================== ★ 业务失败信封的识别位（不是业务数据）====================

    /** 业务失败时的状态码（枚举见 {@code com.fitmind.common.domain.R}）；**成功（裸 VO）时为 null** */
    private Integer code;

    /** 业务失败原因（人话）；成功时为 null */
    private String msg;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Item {
        private Long courseId;
        private String courseName;
        private String coverUrl;
        private Integer price;
        private Integer validDuration;
    }
}
