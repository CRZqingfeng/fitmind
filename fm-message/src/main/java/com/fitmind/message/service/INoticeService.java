package com.fitmind.message.service;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.domain.query.PageQuery;
import com.fitmind.message.domain.dto.NoticeTemplateFormDTO;
import com.fitmind.message.domain.po.NoticeTemplate;

import java.util.Map;

/**
 * 通知服务（模板 + 发送）。
 *
 * <p>模板管理和发送放在同一个服务里，是因为它们<b>本来就耦合</b>：
 * 发送必须查模板（拿文案和"要不要发短信"的开关），
 * 拆成两个服务只会多一层来回调用。</p>
 */
public interface INoticeService {

    /**
     * 渲染模板文案：把 {@code {key}} 替换成参数值。
     *
     * <p>参数里没有的占位符<b>原样保留</b>（而不是替换成 null）——
     * 这样配错模板时用户看到的是 {@code {courseName}} 而不是空白，
     * <b>问题会在界面上暴露出来</b>，而不是静默产出一句读不通的话。</p>
     */
    String render(String template, Map<String, Object> params);

    /**
     * ★ 统一发送入口：按模板代号给指定用户发通知。
     *
     * <p>三步：查模板 → 渲染 → 写用户信箱（+ 可选发短信）。</p>
     *
     * <p><b>幂等</b>：{@code sourceId} 与来源类型组成唯一键，
     * 同一笔业务重复发送时第二次插入冲突、直接忽略 ——
     * 调用方（MQ 消费者 / 其它服务）不需要自己去重。</p>
     *
     * @param templateCode 模板代号（如 {@code refund-success}）
     * @param userId       收件人
     * @param params       模板参数
     * @param sourceId     业务id（退款申请id / 订单id），做幂等键
     */
    void sendByTemplate(String templateCode, Long userId, Map<String, Object> params, Long sourceId);

    // ==================== 模板管理 ====================

    Long saveTemplate(NoticeTemplateFormDTO dto);

    void updateTemplate(NoticeTemplateFormDTO dto);

    void removeTemplate(Long id);

    /** 模板分页（直接返回 PO：这是管理端的内部工具接口，没有脱敏/聚合需求） */
    PageDTO<NoticeTemplate> queryTemplates(PageQuery query);

    NoticeTemplate getTemplate(Long id);
}
