package com.fitmind.aigc.service;

import com.fitmind.aigc.domain.dto.ChatRequestDTO;
import com.fitmind.aigc.domain.vo.ChatEventVO;
import com.fitmind.aigc.domain.vo.ChatResultVO;
import reactor.core.publisher.Flux;

/** 对话编排：一趟"提问 → 模型 → 回答"的完整过程 */
public interface IChatService {

    /**
     * 流式对话（SSE）。
     *
     * <p>返回的是事件流而不是文本流：事件类型见 {@code AigcConstants}。
     * 之所以要事件而不是纯文本，是因为**工具调用的卡片数据走不了纯文本通道**
     * （M6-4：课程卡片的字段结构不能混在自然语言里让前端去解析）。</p>
     */
    Flux<ChatEventVO> stream(ChatRequestDTO dto);

    /**
     * 非流式对话（一次拿完整结果）。
     *
     * <p>给"不方便用 SSE 的调用方"与"需要精确断言指标的验收脚本"使用。</p>
     */
    ChatResultVO chat(ChatRequestDTO dto);

    /**
     * 停止某个会话正在生成的那一轮（分布式打断）。
     *
     * <p>★ <b>为什么不能只靠"客户端断开连接"</b>：断开只能停下
     * <b>本实例</b>正在跑的那条流。多实例部署下，"点停止"的请求会被负载均衡
     * 打到另一个实例 —— 那边没有这条流可断，只能通过共享的 Redis 标记
     * 通知正在生成的那个实例（见 {@code GenerationRegistry}）。</p>
     *
     * <p>⚠️ 归属校验必须在前面做（{@code sessionService} 里按 userId 查一次）：
     * 否则任何人拿到别人的 sessionId 就能把对方的回答掐掉。</p>
     *
     * @return 被停止的 requestId；{@code null} 表示该会话当前没有在生成的轮次
     *         （用户点了一个已经结束的停止按钮，属正常情况，不是错误）
     */
    String stop(String sessionId);

    /**
     * 当前用户的今日配额（限流的观测口）。
     *
     * <p>★ 暴露它的目的有两个：前端能显示"今日还剩 N 次"（用户心里有数，
     * 而不是突然被拒），排查时也能一眼看出"他被限住了没有"。</p>
     */
    java.util.Map<String, Object> quota();

    /**
     * 某个会话的"上下文窗口体检报告"。
     *
     * <p>★ 排查多轮对话问题（"它怎么不记得刚才说的了"）的第一站：
     * 一眼能看到摘要、消息条数、TTL 以及最近几条的预览。</p>
     */
    java.util.Map<String, Object> memoryView(String sessionId);
}
