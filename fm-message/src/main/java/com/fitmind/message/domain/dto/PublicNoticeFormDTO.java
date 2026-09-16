package com.fitmind.message.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公告发布表单。
 *
 * <p>{@code pushTime} 留空 = 立即发布；填未来时间 = 定时发布
 * （由 {@code publishNoticeJob} 到点执行）。</p>
 */
@Data
@Schema(description = "公告发布")
public class PublicNoticeFormDTO {

    @Schema(description = "公告类型：0-系统 3-其它")
    private Integer type;

    @Schema(description = "公告标题")
    @NotBlank(message = "公告标题不能为空")
    private String title;

    @Schema(description = "公告内容")
    @NotBlank(message = "公告内容不能为空")
    private String content;

    @Schema(description = "发布时间（留空=立即）")
    private LocalDateTime pushTime;

    @Schema(description = "失效时间（留空=按默认有效期）")
    private LocalDateTime expireTime;
}
