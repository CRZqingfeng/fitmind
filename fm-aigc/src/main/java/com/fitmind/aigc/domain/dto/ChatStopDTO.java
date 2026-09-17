package com.fitmind.aigc.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 停止生成请求（M6-5）。
 *
 * <p>★ 请求体里<b>只有 sessionId，没有 requestId</b>。这是刻意的：
 * 停止的语义是"这个会话现在这一轮别答了"，而"现在这一轮是哪个"
 * <b>由服务端从 Redis 标记里读出来</b>（见 {@code GenerationRegistry}）。
 * 让前端传 requestId 会引入一个竞态：前端手上的可能是上一轮的 id，
 * 于是它"点停止"却停不掉当前这轮 —— 而前端并不知道自己拿的是旧的。</p>
 */
@Data
@Accessors(chain = true)
@Schema(description = "停止生成请求")
public class ChatStopDTO {

    @Schema(description = "会话id")
    @NotBlank(message = "会话id不能为空")
    private String sessionId;
}
