package com.fitmind.message.service;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.domain.query.PageQuery;
import com.fitmind.message.domain.dto.PublicNoticeFormDTO;
import com.fitmind.message.domain.po.PublicNotice;

/**
 * 公告服务（<b>pull 模式</b>）。
 *
 * <p>公告与定向通知的区别：公告是"给所有人看的同一句话"，
 * 所以<b>只存一行</b>，用户来查消息时才展开（{@link #expandToInbox}）。</p>
 *
 * <p><b>★ 为什么这里没有"定时发布任务"</b>：常见实现为此专门建了一张
 * {@code notice_task} 表和一个定时任务。但那是 <b>push 模式的必需品</b> ——
 * 要在"明早 10 点"给所有用户各写一行收件箱记录，就必须有定时器。
 * 我们是 pull 模式，"定时发布"退化成"把 {@code pushTime} 写成未来的时间点"：
 * {@link #expandToInbox} 里本来就有 {@code push_time <= now} 的条件，
 * 到点自然可见。<b>一行字段省掉一整张表和一套重复执行的状态机。</b></p>
 */
public interface IPublicNoticeService {

    /** 发布公告（pushTime 留空=立即；填未来时间=定时生效，无需定时任务） */
    Long publish(PublicNoticeFormDTO dto);

    /** 公告分页（管理端） */
    PageDTO<PublicNotice> queryPage(PageQuery query);

    /**
     * ★ 把"用户还没看过的公告"展开进他的信箱（幂等）。
     *
     * <p>在用户查消息时调用。正确性由 {@code user_inbox} 的
     * {@code uk_user_source(user_id, source_type, source_id)} 保证 ——
     * 展开范围算错了最多是"多查几条"，不会产生重复消息。</p>
     */
    void expandToInbox(Long userId);
}
