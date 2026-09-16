package com.fitmind.message.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.utils.UserContext;
import com.fitmind.message.domain.po.UserInbox;
import com.fitmind.message.domain.query.UserInboxQuery;
import com.fitmind.message.domain.vo.UserInboxPageVO;
import com.fitmind.message.mapper.UserInboxMapper;
import com.fitmind.message.service.IPublicNoticeService;
import com.fitmind.message.service.IUserInboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 用户信箱服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserInboxServiceImpl implements IUserInboxService {

    private final UserInboxMapper inboxMapper;
    private final IPublicNoticeService publicNoticeService;

    /**
     * 我的消息分页。
     *
     * <p><b>★ 先展开公告，再查信箱</b> —— 顺序不能反。公告是 pull 模式，
     * 只有"用户来查"这个动作才会把它变成他自己的记录。
     * 如果先查后展开，用户永远看不到自己刚收到的第一条公告
     * （要等下次刷新才出现）。</p>
     */
    @Override
    public PageDTO<UserInboxPageVO> queryMyMessages(UserInboxQuery query) {
        Long userId = UserContext.getUser();
        // 1. 展开新公告（幂等，见 PublicNoticeServiceImpl#expandToInbox）
        publicNoticeService.expandToInbox(userId);
        // 2. 查自己的信箱。★ userId 取当前登录用户，不接受前端传参
        LocalDateTime now = LocalDateTime.now();
        Page<UserInbox> page = inboxMapper.selectPage(query.toMpPage("push_time", false),
                new LambdaQueryWrapper<UserInbox>()
                        .eq(UserInbox::getUserId, userId)
                        .eq(query.getIsRead() != null, UserInbox::getIsRead, query.getIsRead())
                        .eq(query.getType() != null, UserInbox::getType, query.getType())
                        // 过期的不展示（但记录还在，直到被 cleanupExpired 清理）
                        .and(w -> w.isNull(UserInbox::getExpireTime)
                                .or().gt(UserInbox::getExpireTime, now)));
        return PageDTO.of(page, r -> new UserInboxPageVO()
                .setId(r.getId())
                .setType(r.getType())
                .setTitle(r.getTitle())
                .setContent(r.getContent())
                .setIsRead(r.getIsRead())
                .setPushTime(r.getPushTime()));
    }

    @Override
    public int countUnread() {
        return inboxMapper.countUnread(UserContext.getUser(), LocalDateTime.now());
    }

    @Override
    public void markRead(Long id) {
        UserInbox inbox = inboxMapper.selectById(id);
        if (inbox == null) {
            throw new BizIllegalException("消息不存在");
        }
        // ★ 只能标记自己的：否则改个 id 就能把别人的消息标成已读
        if (!Objects.equals(inbox.getUserId(), UserContext.getUser())) {
            throw new BizIllegalException("只能操作自己的消息");
        }
        if (inbox.getIsRead() != null && inbox.getIsRead() == 1) {
            return;     // 已读 → 幂等返回
        }
        inboxMapper.updateById(new UserInbox().setId(id).setIsRead(1));
    }

    @Override
    public void markAllRead() {
        // 一条 UPDATE 搞定，不做"先查出来再逐条改"—— 未读可能上千条
        inboxMapper.update(null, new LambdaUpdateWrapper<UserInbox>()
                .set(UserInbox::getIsRead, 1)
                .eq(UserInbox::getUserId, UserContext.getUser())
                .eq(UserInbox::getIsRead, 0));
    }

    /**
     * 清理过期站内信。
     *
     * <p>范围收窄到"已过期 <b>且</b> 已读"，理由见接口注释。另外加了一个
     * 时间缓冲：只清"过期超过 7 天"的 —— 用户刚刚划过但还没来得及读的消息
     * 不会因为一个跨越凌晨的定时任务突然消失。</p>
     */
    @Override
    public int cleanupExpired() {
        LocalDateTime deadline = LocalDateTime.now().minusDays(7);
        int n = inboxMapper.delete(new LambdaQueryWrapper<UserInbox>()
                .isNotNull(UserInbox::getExpireTime)
                .lt(UserInbox::getExpireTime, deadline)
                .eq(UserInbox::getIsRead, 1));
        log.info("过期站内信清理完成：{} 条（条件：过期超过 7 天且已读）", n);
        return n;
    }
}
