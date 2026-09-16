package com.fitmind.message.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 我的消息（列表项）。
 */
@Data
@Accessors(chain = true)
@Schema(description = "我的消息")
public class UserInboxPageVO {

    @Schema(description = "消息id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "通知类型：0-系统 1-笔记 2-问答 3-其它 4-私信")
    private Integer type;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "内容")
    private String content;

    @Schema(description = "是否已读：0-未读 1-已读")
    private Integer isRead;

    @Schema(description = "推送时间")
    private LocalDateTime pushTime;
}
