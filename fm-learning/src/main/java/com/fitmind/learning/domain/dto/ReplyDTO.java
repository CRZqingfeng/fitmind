package com.fitmind.learning.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 回答 / 评论请求。
 *
 * <p>⚠️ 与常见实现的一个差异：<b>没有 {@code isStudent} 字段</b>。
 * 常见实现由前端传"我是不是学生"来决定加不加积分 —— 这是可以伪造的。
 * 我们改由服务端查 {@code userClient.queryUserType(userId)} 判定
 * （学员 type=2 才加积分，教练/管理员回答不加）。</p>
 */
@Data
@Schema(description = "互动回答请求")
public class ReplyDTO {

    @Schema(description = "问题id", example = "1")
    @NotNull(message = "问题id不能为空")
    private Long questionId;

    @Schema(description = "上级回答id；回答顶层问题时留空或传 0", example = "0")
    private Long answerId;

    @Schema(description = "回复的目标回复id（楼中楼）", example = "3")
    private Long targetReplyId;

    @Schema(description = "回复的目标用户id（楼中楼）", example = "2")
    private Long targetUserId;

    @Schema(description = "内容", example = "握距窄一点，手腕保持中立位试试")
    @NotBlank(message = "回答内容不能为空")
    private String content;

    @Schema(description = "是否匿名回答", example = "false")
    private Boolean anonymity;
}
