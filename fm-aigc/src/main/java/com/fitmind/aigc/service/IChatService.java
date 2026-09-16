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
}
