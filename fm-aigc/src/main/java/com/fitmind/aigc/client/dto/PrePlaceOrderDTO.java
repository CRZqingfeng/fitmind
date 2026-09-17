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
