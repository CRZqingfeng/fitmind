package com.fitmind.learning.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 回答 / 评论条目。
 */
@Data
@Schema(description = "互动回答")
public class ReplyVO {

    @Schema(description = "回答id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "问题id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long questionId;

    @Schema(description = "上级回答id（0 表示顶层回答）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long answerId;

    @Schema(description = "回答者id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    @Schema(description = "回答者姓名（匿名时为空）")
    private String userName;

    @Schema(description = "回答者头像（匿名时为空）")
    private String userIcon;

    @Schema(description = "回答者类型：1-管理员 2-学员 3-教练")
    private Integer userType;

    @Schema(description = "内容")
    private String content;

    @Schema(description = "回复目标用户id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long targetUserId;

    @Schema(description = "回复目标回复id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long targetReplyId;

    @Schema(description = "回复目标用户姓名")
    private String targetUserName;

    @Schema(description = "下属评论数量")
    private Integer replyTimes;

    @Schema(description = "点赞数")
    private Integer likedTimes;

    @Schema(description = "当前用户是否点过赞（由 fm-remark 实时查，非冗余字段）")
    private Boolean liked;

    @Schema(description = "是否匿名")
    private Boolean anonymity;

    @Schema(description = "是否被隐藏（仅管理端可见该字段）")
    private Boolean hidden;

    @Schema(description = "回答时间")
    private LocalDateTime createTime;
}
