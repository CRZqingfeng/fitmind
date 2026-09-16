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

    /**
     * 本次使用的编排策略，可选：{@code ROUTE} / {@code ENHANCE} / {@code SIMPLE}。
     *
     * <p>不传就用配置里的默认值（{@code fm.ai.chat.type}）。</p>
     *
     * <p>★ 为什么允许请求级覆盖 —— 编排策略本来是部署级决策，看起来不该由单次请求决定。
     * 但有两个场景确实需要它：
     * ① <b>演示与对比</b>：同一个提问在三种策略下各跑一遍，看延迟、模型调用次数、
     *    回答质量的差别（这个对比本身就很能说明"多 Agent 到底值不值"）；
     * ② <b>验收</b>：脚本要能覆盖三条路径，而不是"改配置 + 重启 + 再跑一遍"。
     * 线上不会有人传这个参数（前端也不会给入口），所以它的风险面很小。</p>
     */
    @Schema(description = "编排策略（可选）：ROUTE / ENHANCE / SIMPLE")
    private String chatType;
}
