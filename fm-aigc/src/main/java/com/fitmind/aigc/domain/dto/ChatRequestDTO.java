package com.fitmind.aigc.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@Schema(description = "发起一轮对话")
public class ChatRequestDTO {

    /**
     * 会话id。为空时服务端新建一个会话并把 id 回给前端。
     *
     * <p>★ 注意这里<b>只接收 sessionId，不接收 userId</b> —— 用户身份永远从
     * {@code UserContext} 取。让前端传 userId 等于把越权入口开在最外层，
     * 而 LLM 场景里这个风险还会被放大（模型可能"编造"参数，见 M6-4 的工具安全）。</p>
     */
    @Size(max = 64, message = "会话id过长")
    @Schema(description = "会话id，不传则新建会话")
    private String sessionId;

    @NotBlank(message = "说点什么吧")
    @Size(max = 1000, message = "单次提问不超过 1000 字")
    @Schema(description = "提问内容")
    private String content;
}
