package com.fitmind.message.service;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.message.domain.query.UserInboxQuery;
import com.fitmind.message.domain.vo.UserInboxPageVO;

/**
 * 用户信箱服务（用户端读消息 + 过期清理）。
 */
public interface IUserInboxService {

    /**
     * 我的消息分页。
     *
     * <p>★ <b>这个方法里藏着一个关键动作：先展开公告</b>。
     * 公告是 pull 模式（全员公告只写一行），用户来查消息时才把它展开成
     * 自己的信箱记录 —— 见 {@code PublicNoticeService#expandToInbox}。</p>
     */
    PageDTO<UserInboxPageVO> queryMyMessages(UserInboxQuery query);

    /** 未读消息数（小程序红点） */
    int countUnread();

    /** 标记已读（只能标记自己的） */
    void markRead(Long id);

    /** 全部已读 */
    void markAllRead();

    /**
     * 清理过期的站内信（定时任务调用）。
     *
     * <p><b>为什么只清"已过期且已读"的</b>：站内信表会随着业务持续增长
     * （每笔支付、每笔退款都产生一条）。但清理是有风险的动作 ——
     * 清错了用户会发现"我的消息少了"。所以把范围收窄到
     * <b>已经过了展示期、且用户自己已经看过</b>的那部分：
     * 这些记录在界面上本来就不可见（查询时会过滤 expire_time），
     * 删掉不会影响任何人的使用体验。</p>
     *
     * @return 实际清理条数
     */
    int cleanupExpired();
}
