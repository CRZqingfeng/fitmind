package com.fitmind.message.constants;

import com.fitmind.api.constants.NoticeTemplateCode;

/**
 * 消息模块常量。
 *
 * <p>通知类型、来源类型、模板状态这三组值同时出现在数据库注释、Java 常量
 * 和验收脚本里。给它们起名字放在一处，比在三个地方各写一遍数字强 ——
 * 这也是 M4-3 踩过"错误提示文案与代码校验不一致"之后养成的习惯。</p>
 */
public interface MessageConstants {

    // ==================== 通知类型（notice_template.type / user_inbox.type） ====================
    /** 系统通知（购课成功、退款到账这类业务通知都归这里） */
    int TYPE_SYSTEM = 0;
    /** 笔记通知（M3-3 的笔记被收藏/点赞，暂未接通） */
    int TYPE_NOTE = 1;
    /** 问答通知（M3-3a，暂未接通） */
    int TYPE_QA = 2;
    /** 其它通知 */
    int TYPE_OTHER = 3;
    /** 私信（用户之间） */
    int TYPE_PRIVATE_MESSAGE = 4;

    // ==================== 用户信箱的来源类型 ====================
    /**
     * ★ 来源类型是幂等键的一半（另一半是 source_id）。
     *
     * <p>{@code uk_user_source(user_id, source_type, source_id)} 靠它区分
     * "同一条公告"和"同一笔订单的通知"—— 两者 id 可能撞上（公告 id=5 与订单 id=5），
     * 带上来源类型才不会互相顶掉。</p>
     */
    /** 来自全员公告（source_id = public_notice.id） */
    int SOURCE_NOTICE = 1;
    /** 来自业务事件（source_id = 业务id，如订单id、退款申请id） */
    int SOURCE_BUSINESS = 2;
    /** 私信 / 手动推送（source_id = 自增用时间戳或业务id） */
    int SOURCE_PRIVATE = 3;

    // ==================== 模板状态 ====================
    /** 草稿（不可用） */
    int TEMPLATE_DRAFT = 0;
    /** 使用中 */
    int TEMPLATE_IN_SERVICE = 1;
    /** 停用 */
    int TEMPLATE_DISABLED = 2;

    // ==================== 短信下发结果 ====================
    int SMS_SUCCESS = 1;
    int SMS_FAILED = 2;

    // ==================== 常用模板代号 ====================
    /** 购课成功（fm-learning 写课表成功后触发） */
    /** 动作考核结果通知（M5：fm-exam 评分后调用内部接口发送） */
    String TPL_ACTION_REVIEWED = NoticeTemplateCode.ACTION_REVIEWED;
    String TPL_PURCHASE_SUCCESS = NoticeTemplateCode.PURCHASE_SUCCESS;
    /** 退款到账（fm-trade 退款成功后触发） */
    String TPL_REFUND_SUCCESS = NoticeTemplateCode.REFUND_SUCCESS;

    // ==================== 渲染占位符 ====================
    /**
     * 占位符的包裹字符：文案里写 {@code {courseName}}，渲染时替换成真实值。
     *
     * <p>用花括号而不是 {@code ${}}：前者不会与 Spring 的配置占位符混淆，
     * 运营在管理端填文案时也不会误以为是系统变量。</p>
     */
    String PLACEHOLDER_PREFIX = "{";
    String PLACEHOLDER_SUFFIX = "}";
}
