package com.fitmind.message.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.domain.query.PageQuery;
import com.fitmind.message.config.MessageProperties;
import com.fitmind.message.constants.MessageConstants;
import com.fitmind.message.domain.dto.PublicNoticeFormDTO;
import com.fitmind.message.domain.po.PublicNotice;
import com.fitmind.message.domain.po.UserInbox;
import com.fitmind.message.mapper.PublicNoticeMapper;
import com.fitmind.message.mapper.UserInboxMapper;
import com.fitmind.message.service.IPublicNoticeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 公告服务实现（pull 模式的关键逻辑都在 {@link #expandToInbox}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicNoticeServiceImpl implements IPublicNoticeService {

    /** 单次展开的上限：防止一个很久没登录的用户一次性拉出上千条公告 */
    private static final int EXPAND_LIMIT = 200;

    private final PublicNoticeMapper publicNoticeMapper;
    private final UserInboxMapper inboxMapper;
    private final MessageProperties properties;

    @Override
    public Long publish(PublicNoticeFormDTO dto) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime pushTime = dto.getPushTime() == null ? now : dto.getPushTime();
        // 失效时间留空则按默认有效期（从发布时间起算，而不是从"现在"起算 ——
        // 否则发布一条"三天后生效"的公告，它的有效期会少三天）
        LocalDateTime expireTime = dto.getExpireTime() == null
                ? pushTime.plusMonths(properties.getNoticeTtlMonths())
                : dto.getExpireTime();
        PublicNotice notice = new PublicNotice()
                .setType(dto.getType() == null ? MessageConstants.TYPE_SYSTEM : dto.getType())
                .setTitle(dto.getTitle())
                .setContent(dto.getContent())
                .setPushTime(pushTime)
                .setExpireTime(expireTime);
        publicNoticeMapper.insert(notice);
        log.info("公告已发布：id={}, title={}, pushTime={}（{}）",
                notice.getId(), notice.getTitle(), pushTime,
                pushTime.isAfter(now) ? "定时生效" : "立即生效");
        return notice.getId();
    }

    @Override
    public PageDTO<PublicNotice> queryPage(PageQuery query) {
        return PageDTO.of(publicNoticeMapper.selectPage(query.toMpPage("push_time", false), null));
    }

    /**
     * ★ 展开公告：把"用户还没看过的公告"复制进他自己的信箱。
     *
     * <p><b>两个设计点</b>：</p>
     * <ol>
     *   <li><b>拉取起点用 {@code >=} 而不是 {@code >}</b>。
     *       边界算重了会被唯一索引挡住（最多多一次 insert 尝试），
     *       算漏了用户就<b>永久看不到那条公告</b>。
     *       <b>在"可能重复"和"可能丢失"之间，选前者</b> ——
     *       重复有兜底，丢失没有。</li>
     *   <li><b>起点只是性能优化，不是正确性依赖</b>。常见实现把
     *       {@code push_time} 当唯一去重依据，于是同一毫秒的两条公告会重复/漏掉；
     *       我们的正确性在 {@code uk_user_source} 上。</li>
     * </ol>
     */
    @Override
    public void expandToInbox(Long userId) {
        if (userId == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        // 1. 起点 = 用户已展开过的最新公告时间；没有或太旧则按公告默认有效期往前推
        LocalDateTime from = inboxMapper.queryLatestExpandTime(userId, MessageConstants.SOURCE_NOTICE);
        LocalDateTime earliest = now.minusMonths(properties.getNoticeTtlMonths());
        if (from == null || from.isBefore(earliest)) {
            from = earliest;
        }
        // 2. 查"起点之后、已到发布时间、且未过期"的公告
        List<PublicNotice> notices = publicNoticeMapper.selectList(new LambdaQueryWrapper<PublicNotice>()
                .ge(PublicNotice::getPushTime, from)
                // 未到发布时间的公告不展开 —— 这就是"定时发布"的全部实现
                .le(PublicNotice::getPushTime, now)
                .and(w -> w.isNull(PublicNotice::getExpireTime)
                        .or().gt(PublicNotice::getExpireTime, now))
                .orderByAsc(PublicNotice::getPushTime)
                .last("LIMIT " + EXPAND_LIMIT));
        if (notices.isEmpty()) {
            return;
        }
        // 3. 逐条写入用户信箱
        int expanded = 0;
        for (PublicNotice n : notices) {
            try {
                inboxMapper.insert(new UserInbox()
                        .setUserId(userId)
                        // 公告展开后的 push_time 沿用公告的发布时间（不是"展开时刻"）——
                        // 否则用户看到的是"我刚登录时收到的公告"，时间线会失真
                        .setType(n.getType())
                        .setTitle(n.getTitle())
                        .setContent(n.getContent())
                        .setIsRead(0)
                        .setPushTime(n.getPushTime())
                        .setExpireTime(n.getExpireTime())
                        .setSourceType(MessageConstants.SOURCE_NOTICE)
                        .setSourceId(n.getId()));
                expanded++;
            } catch (DuplicateKeyException e) {
                // 已经展开过 —— 幂等，正常情况（起点算重了就会走到这里）
                log.debug("公告已展开过，跳过：userId={}, noticeId={}", userId, n.getId());
            }
        }
        if (expanded > 0) {
            log.info("公告已展开到用户信箱：userId={}, 本次新增 {} 条", userId, expanded);
        }
    }
}
