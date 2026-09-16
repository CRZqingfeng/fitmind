package com.fitmind.api.constants;

/**
 * 通知模板代号（跨服务契约）。
 *
 * <p><b>★ 为什么这几个字符串要放公共模块</b>：它们不是 fm-message 的内部细节 ——
 * 模板由 fm-message 预置（写在 {@code sql/fm_message.sql} 里），
 * 但<b>代号由调用方传进来</b>。也就是说：</p>
 *
 * <ul>
 *   <li>fm-message 侧：模板没落库 → 运行期抛"模板不存在"</li>
 *   <li>fm-exam 侧：代号打错一个字母 → 编译期就该报错</li>
 * </ul>
 *
 * <p>如果代号只写在 fm-message 里，后者就做不到 ——
 * 调用方只能手敲字符串，错了要到跑起来才发现。
 * 这和 M4-4 那个"验签参数两边必须严格对齐"是同一类问题：
 * <b>跨越服务边界的东西，两边必须共用同一份定义。</b></p>
 */
public interface NoticeTemplateCode {

    /** 购课成功（由 fm-message 消费 order.pay 事件触发） */
    String PURCHASE_SUCCESS = "purchase-success";

    /** 退款到账（由 fm-message 消费 order.refund 事件触发） */
    String REFUND_SUCCESS = "refund-success";

    /** 动作考核结果（由 fm-exam 评分后主动调用内部接口触发） */
    String ACTION_REVIEWED = "action-reviewed";
}
