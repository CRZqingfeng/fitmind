package com.fitmind.learning.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 笔记条目（用户端）。
 *
 * <p>两个容易混的字段在这里说清楚：</p>
 * <ul>
 *   <li>{@code isGathered} —— 本条笔记本身是「我从别人那儿采集来的」</li>
 *   <li>{@code gatheredByMe} —— 「我采集过这条笔记」（公共列表里用来点亮收藏图标）</li>
 * </ul>
 */
@Data
@Schema(description = "学习笔记")
public class NoteVO {

    @Schema(description = "笔记id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "持有者id（采集后是采集者）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    @Schema(description = "原作者id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long authorId;

    @Schema(description = "原作者姓名（其实是谁都能看到，笔记没有匿名）")
    private String authorName;

    @Schema(description = "原作者头像")
    private String authorIcon;

    @Schema(description = "课程id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long courseId;

    @Schema(description = "训练日id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long chapterId;

    @Schema(description = "训练动作id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sectionId;

    @Schema(description = "笔记对应的视频时间点（秒）")
    private Integer noteMoment;

    @Schema(description = "笔记内容")
    private String content;

    @Schema(description = "是否隐私笔记")
    private Boolean isPrivate;

    @Schema(description = "本条是否采集自他人")
    private Boolean isGathered;

    @Schema(description = "当前用户是否采集过这条笔记")
    private Boolean gatheredByMe;

    @Schema(description = "点赞数")
    private Integer likedTimes;

    @Schema(description = "当前用户是否点过赞（由 fm-remark 实时查）")
    private Boolean liked;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
