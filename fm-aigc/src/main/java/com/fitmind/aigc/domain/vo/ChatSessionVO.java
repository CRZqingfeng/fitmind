package com.fitmind.aigc.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@Schema(description = "会话列表项")
public class ChatSessionVO {

    @Schema(description = "会话id")
    private String sessionId;

    @Schema(description = "会话标题（首轮提问的前若干字）")
    private String title;

    @Schema(description = "消息条数")
    private Integer msgCount;

    @Schema(description = "最后一条消息的预览（会话列表用它提高辨识度）")
    private String lastMessage;

    @Schema(description = "最后活跃时间")
    private LocalDateTime lastActive;

    private LocalDateTime createTime;
}
