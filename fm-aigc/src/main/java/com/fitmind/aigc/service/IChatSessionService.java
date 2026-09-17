package com.fitmind.aigc.service;

import com.fitmind.aigc.domain.po.ChatMessage;
import com.fitmind.aigc.domain.po.ChatSession;
import com.fitmind.aigc.domain.vo.ChatMessageVO;
import com.fitmind.aigc.domain.vo.ChatSessionVO;
import com.fitmind.common.domain.dto.PageDTO;

import java.util.List;

/**
 * 会话与消息的存取。
 *
 * <p>对话编排（{@code IChatService}）只负责"跟模型交互"，会话与消息的读写都在这里 ——
 * 两者的变化原因不同：前者随提示词/模型/编排策略变，后者随表结构和查询需求变。</p>
 */
public interface IChatSessionService {

    /**
     * 取会话；不存在则按首轮提问新建一个。
     *
     * @param sessionId    前端带来的会话id，为空表示新建
     * @param userId       归属用户（<b>由调用方从 UserContext 取，绝不来自请求体</b>）
     * @param firstContent 首轮提问内容，用于生成会话标题
     */
    ChatSession getOrCreate(String sessionId, Long userId, String firstContent);

    /** 我的会话列表（按最后活跃时间倒序） */
    PageDTO<ChatSessionVO> pageMine(int pageNo, int pageSize);

    /**
     * 会话的消息历史（按时间正序）。
     *
     * <p>用户身份从 {@code UserContext} 取，归属校验在 SQL 的 WHERE 里完成 ——
     * <b>不是"先查出来再判断是不是自己的"</b>。后一种写法只要有一个人忘了判断就越权，
     * 而前一种"想忘也忘不掉"。</p>
     */
    List<ChatMessageVO> listMessages(String sessionId);

    /** 落一条消息（提问或回答） */
    void saveMessage(ChatMessage message);

    /** 会话活跃度更新（消息数 +delta、刷新最后活跃时间） */
    void touch(String sessionId, Long userId, int delta);

    /**
     * 会话活跃度更新 + 刷新"最后一条消息预览"（M6-5）。
     *
     * @param lastMessage 最后一条消息正文（会截断），为空则不更新该列
     */
    void touch(String sessionId, Long userId, int delta, String lastMessage);

    /**
     * 断言某个会话属于当前登录用户，否则抛业务异常。
     *
     * <p>★ 与 {@link #listMessages} 的区别：这里只要"归属"，不要消息正文。
     * 停止生成（{@code /chat/stop}）与查看上下文窗口（{@code /chat/sessions/**}{@code /memory}）
     * 都需要它 —— <b>没有这道校验，任何人拿到别人的 sessionId
     * 就能把对方正在生成的回答掐掉、或者看到对方喂给模型的上下文。</b></p>
     */
    void assertMine(String sessionId);
}
