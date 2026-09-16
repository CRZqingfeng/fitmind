package com.fitmind.learning.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 笔记条目（管理端）。
 */
@Data
@Schema(description = "管理端学习笔记")
public class NoteAdminVO {

    @Schema(description = "笔记id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "持有者id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    @Schema(description = "原作者id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long authorId;

    @Schema(description = "原作者姓名")
    private String authorName;

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

    @Schema(description = "笔记对应的视频时间点（秒）")
    private Integer noteMoment;

    @Schema(description = "笔记内容")
    private String content;

    @Schema(description = "是否隐私笔记")
    private Boolean isPrivate;

    @Schema(description = "是否被折叠")
    private Boolean hidden;

    @Schema(description = "被折叠原因")
    private String hiddenReason;

    @Schema(description = "本条是否采集自他人")
    private Boolean isGathered;

    @Schema(description = "被采集的原笔记id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long gatheredNoteId;

    @Schema(description = "被多少人采集过")
    private Integer usedTimes;

    @Schema(description = "点赞数")
    private Integer likedTimes;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
