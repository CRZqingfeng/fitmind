package com.fitmind.aigc.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 一个 SSE 事件。
 *
 * <p>事件类型见 {@code AigcConstants.EVENT_DATA / EVENT_PARAM / EVENT_STOP}。</p>
 *
 * <p>★ 为什么要有 {@code requestId} 而不是只靠 sessionId：
 * 同一个会话里可能连续发问，"停止生成"必须能精确停止<b>当前这一轮</b>。
 * 只按 sessionId 的话，用户点一次停止会把下一轮也带走（常见实现的做法就是只有
 * sessionId，同会话连发会互相干扰）。</p>
 */
@Data
@Accessors(chain = true)
@Schema(description = "SSE 事件")
public class ChatEventVO {

    @Schema(description = "事件类型：DATA-增量文本 / PARAM-卡片参数 / STOP-结束")
    private String type;

    @Schema(description = "载荷：DATA 是文本分片，PARAM 是 JSON 字符串")
    private String data;

    @Schema(description = "本轮请求id")
    private String requestId;

    public static ChatEventVO data(String text) {
        return new ChatEventVO().setType("DATA").setData(text);
    }

    public static ChatEventVO param(String json) {
        return new ChatEventVO().setType("PARAM").setData(json);
    }

    public static ChatEventVO stop() {
        return new ChatEventVO().setType("STOP");
    }
}
