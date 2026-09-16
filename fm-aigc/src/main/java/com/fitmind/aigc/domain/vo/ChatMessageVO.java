package com.fitmind.aigc.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@Schema(description = "历史消息")
public class ChatMessageVO {

    @Schema(description = "消息id")
    private Long id;

    @Schema(description = "角色：user / assistant")
    private String role;

    @Schema(description = "正文")
    private String content;

    @Schema(description = "产出该回答的 Agent（M6-2 起有值）")
    private String agentType;

    @Schema(description = "模型通道")
    private String provider;

    /**
     * 卡片数据。
     *
     * <p>★ 历史记录里必须把它带上：前端翻回三天前的对话时，
     * 那张课程推荐卡片得能**重新渲染出来** —— 如果卡片数据只活在
     * 当次 SSE 的 PARAM 事件里，翻历史就只剩一句"我帮你找到了这些课程"，
     * 用户看着一段没有内容的推荐，体验是断的。
     * （常见实现把卡片参数放在单机内存 Map 里，重启就没了。）</p>
     */
    @Schema(description = "卡片数据（用于历史重渲染）")
    private Object params;

    @Schema(description = "本轮调用过的工具")
    private Object toolCalls;

    // 历史列表也带上指标：前端可以显示"本次回答耗时 1.2s"，
    // 运营侧则可以直接看哪一轮特别慢
    private Integer firstTokenMs;

    private Integer totalMs;

    private LocalDateTime createTime;
}
