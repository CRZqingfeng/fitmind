package com.fitmind.learning.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问题条目（管理端）。
 *
 * <p>与用户端的差别：<b>匿名问题也返回真实提问者信息</b> ——
 * 管理端必须能看到是谁提的，否则无法处理。</p>
 */
@Data
@Schema(description = "管理端互动问题")
public class QuestionAdminVO {

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

    @Schema(description = "课程名称")
    private String courseName;

    @Schema(description = "训练日id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long chapterId;

    @Schema(description = "训练日名称")
    private String chapterName;

    @Schema(description = "训练动作id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sectionId;

    @Schema(description = "训练动作名称")
    private String sectionName;

    @Schema(description = "提问者id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    @Schema(description = "提问者姓名")
    private String userName;

    @Schema(description = "提问者头像")
    private String userIcon;

    @Schema(description = "最新回答id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long latestAnswerId;

    @Schema(description = "回答数量")
    private Integer answerTimes;

    @Schema(description = "是否匿名")
    private Boolean anonymity;

    @Schema(description = "是否被隐藏")
    private Boolean hidden;

    @Schema(description = "管理端状态：0-未查看 1-已查看")
    private Integer status;

    @Schema(description = "提问时间")
    private LocalDateTime createTime;
}
