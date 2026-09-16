package com.fitmind.message.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 通知模板表单（新增 / 修改共用）。
 */
@Data
@Schema(description = "通知模板")
public class NoticeTemplateFormDTO {

    @Schema(description = "模板id（修改时必传）")
    private Long id;

    @Schema(description = "模板名称")
    @NotBlank(message = "模板名称不能为空")
    private String name;

    @Schema(description = "模板代号（程序按它找模板，如 purchase-success）")
    @NotBlank(message = "模板代号不能为空")
    private String code;

    @Schema(description = "通知类型：0-系统 1-笔记 2-问答 3-其它 4-私信")
    private Integer type;

    @Schema(description = "模板状态：0-草稿 1-使用中 2-停用")
    private Integer status;

    @Schema(description = "通知标题（支持 {占位符}）")
    @NotBlank(message = "标题不能为空")
    private String title;

    @Schema(description = "通知内容（支持 {占位符}）")
    @NotBlank(message = "内容不能为空")
    private String content;

    @Schema(description = "是否同时发短信：0-否 1-是")
    private Integer isSmsTemplate;
}
