package com.fitmind.aigc.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.aigc.constants.AigcConstants;
import com.fitmind.aigc.constants.AigcErrorInfo;
import com.fitmind.aigc.domain.po.ChatMessage;
import com.fitmind.aigc.domain.po.ChatSession;
import com.fitmind.aigc.domain.vo.ChatMessageVO;
import com.fitmind.aigc.domain.vo.ChatSessionVO;
import com.fitmind.aigc.mapper.ChatMessageMapper;
import com.fitmind.aigc.mapper.ChatSessionMapper;
import com.fitmind.aigc.service.IChatSessionService;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionServiceImpl extends ServiceImpl<ChatSessionMapper, ChatSession>
        implements IChatSessionService {

    private final ChatMessageMapper messageMapper;

    @Override
    public ChatSession getOrCreate(String sessionId, Long userId, String firstContent) {
        boolean given = StrUtil.isNotBlank(sessionId);
        if (given) {
            ChatSession exist = findByUserAndSession(sessionId, userId);
            if (exist != null) {
                return exist;
            }
        }

        // ★ 前端带了一个库里没有的 sessionId 是<b>正常情况</b>，不是错误。
        //   前端一般自己生成 sessionId（首轮就带上），这样就不用"先建会话再发消息"
        //   两次往返。所以这里直接按它建库，而不是报错让前端补一次请求。
        String sid = given ? sessionId : IdUtil.getSnowflakeNextIdStr();
        ChatSession session = new ChatSession()
                .setSessionId(sid)
                .setUserId(userId)
                .setTitle(buildTitle(firstContent))
                .setMsgCount(0)
                .setLastActive(LocalDateTime.now());
        try {
            baseMapper.insert(session);
        } catch (DuplicateKeyException e) {
            // 同一用户并发发首轮消息时会撞 uk_user_session。
            // ★ 这里不引入分布式锁：唯一索引已经把"重复建会话"这件事挡在数据库层了，
            //   应用侧只需要"撞了就回查一次"。加锁是把一个已经解决的问题又做一遍，
            //   而且多了一个可能死锁的点。（与 M4-1 订单、M4-4 退款同一套取舍）
            log.debug("会话并发创建，回查已存在记录：sessionId={}, userId={}", sid, userId);
            return findByUserAndSession(sid, userId);
        }
        return session;
    }

    @Override
    public PageDTO<ChatSessionVO> pageMine(int pageNo, int pageSize) {
        Long userId = UserContext.getUser();
        int size = Math.min(Math.max(pageSize, 1), AigcConstants.SESSION_PAGE_MAX_SIZE);
        Page<ChatSession> page = new Page<>(Math.max(pageNo, 1), size);
        Page<ChatSession> result = baseMapper.selectPage(page,
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getUserId, userId)
                        .orderByDesc(ChatSession::getLastActive));
        return PageDTO.of(result, this::toSessionVO);
    }

    @Override
    public List<ChatMessageVO> listMessages(String sessionId) {
        Long userId = UserContext.getUser();
        ChatSession session = findByUserAndSession(sessionId, userId);
        if (session == null) {
            // ★ 查不到就说"不存在"，不区分"不是你自己的" —— 见 AigcErrorInfo 里的说明
            throw new BizIllegalException(AigcErrorInfo.Msg.SESSION_NOT_MINE);
        }
        return messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getUserId, userId)
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getId))
                .stream().map(this::toMessageVO).toList();
    }

    @Override
    public void saveMessage(ChatMessage message) {
        try {
            messageMapper.insert(message);
        } catch (DuplicateKeyException e) {
            // ★ uk_request_role(request_id, role) 是幂等的兜底：SSE 重连、
            //   客户端重试、用户刷新页面重发，都可能让同一轮被落库两次。
            //   撞索引说明"这一轮的消息已经写过了"，直接跳过而不是抛异常 ——
            //   因为对使用者来说"回答已经有了"就是正确结果。
            log.debug("消息已存在，跳过重复写入：requestId={}, role={}",
                    message.getRequestId(), message.getRole());
        }
    }

    @Override
    public void touch(String sessionId, Long userId, int delta) {
        touch(sessionId, userId, delta, null);
    }

    @Override
    public void touch(String sessionId, Long userId, int delta, String lastMessage) {
        baseMapper.update(null, new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getSessionId, sessionId)
                // ★ 用 SQL 自增而不是"读出来 + delta 再写回"：后者在并发下会丢更新
                //   （两个请求都读到 5，各自写 6，结果少算一条）。
                //   delta 是代码里的固定值（1 或 2），不是用户输入，没有注入风险。
                .setSql("msg_count = msg_count + " + delta)
                .set(ChatSession::getLastActive, LocalDateTime.now())
                // ★ 条件 set：MyBatis-Plus 的 set(false, ...) 不生成该列，
                //   于是"不传预览"与"把预览写成空"不会混淆
                .set(StrUtil.isNotBlank(lastMessage), ChatSession::getLastMessage, preview(lastMessage)));
    }

    @Override
    public void assertMine(String sessionId) {
        Long userId = UserContext.getUser();
        if (StrUtil.isBlank(sessionId) || findByUserAndSession(sessionId, userId) == null) {
            // ★ 查不到就说"不存在"，不区分"不是你自己的" —— 见 AigcErrorInfo 里的说明
            throw new BizIllegalException(AigcErrorInfo.Msg.SESSION_NOT_MINE);
        }
    }

    // ==================== 内部 ====================

    private ChatSession findByUserAndSession(String sessionId, Long userId) {
        return baseMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getSessionId, sessionId));
    }

    private String buildTitle(String content) {
        String t = StrUtil.blankToDefault(content, "新会话").trim().replaceAll("\\s+", " ");
        return t.length() <= AigcConstants.SESSION_TITLE_MAX_LEN
                ? t
                : t.substring(0, AigcConstants.SESSION_TITLE_MAX_LEN);
    }

    private ChatSessionVO toSessionVO(ChatSession s) {
        return new ChatSessionVO()
                .setSessionId(s.getSessionId())
                .setTitle(s.getTitle())
                .setMsgCount(s.getMsgCount())
                .setLastMessage(s.getLastMessage())
                .setLastActive(s.getLastActive())
                .setCreateTime(s.getCreateTime());
    }

    /**
     * 会话列表的"最后一句"预览。
     *
     * <p>★ 做"空白压缩 + 截断"而不是原样存：会话列表是一行文字，
     * 而 AI 的回答里换行非常常见 —— 原样存进列表会把行高撑变形，
     * 这种问题在前端看起来"是前端的事"，其实是数据没收拾。</p>
     */
    private String preview(String content) {
        if (StrUtil.isBlank(content)) {
            return null;
        }
        String t = content.trim().replaceAll("\\s+", " ");
        return t.length() <= AigcConstants.SESSION_PREVIEW_MAX_LEN
                ? t : t.substring(0, AigcConstants.SESSION_PREVIEW_MAX_LEN);
    }

    private ChatMessageVO toMessageVO(ChatMessage m) {
        return new ChatMessageVO()
                .setId(m.getId())
                .setRole(m.getRole())
                .setContent(m.getContent())
                .setAgentType(m.getAgentType())
                .setProvider(m.getProvider())
                .setParams(m.getParams())
                .setToolCalls(m.getToolCalls())
                .setFirstTokenMs(m.getFirstTokenMs())
                .setTotalMs(m.getTotalMs())
                .setCreateTime(m.getCreateTime());
    }
}
