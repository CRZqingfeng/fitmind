package com.fitmind.learning.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问题条目（用户端列表 / 详情）。
 */
@Data
@Schema(description = "互动问题")
public class QuestionVO {

    @Schema(description = "问题id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "问题描述")
    private String description;

    @Schema(description = "课程id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long courseId;

    @Schema(description = "训练日id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long chapterId;

    @Schema(description = "训练动作id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sectionId;

    @Schema(description = "提问者id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    @Schema(description = "提问者姓名（匿名时为空）")
    private String userName;

    @Schema(description = "提问者头像（匿名时为空）")
    private String userIcon;

    @Schema(description = "是否匿名")
    private Boolean anonymity;

    @Schema(description = "最新回答内容")
    private String latestReplyContent;

    @Schema(description = "最新回答者姓名（匿名时为空）")
    private String latestReplyUser;

    @Schema(description = "回答数量")
    private Integer answerTimes;

    @Schema(description = "提问时间")
    private LocalDateTime createTime;
}
