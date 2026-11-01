package com.fitmind.plan.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 接受预览的入参。
 *
 * <p>★ <b>只有一个字段，而且是服务端生成的 previewId —— 没有 planId / dietId。</b>
 * 这是刻意的：如果前端能传 planId，那就能"接受别人的计划"。
 * 服务端只认"这个 previewId 是不是你的草稿"（见 {@code PlanAiServiceImpl.apply}）。</p>
 */
@Data
@Schema(description = "接受 AI 计划预览")
public class AiApplyDTO {

    @Schema(description = "预览 id（生成预览时返回）", example = "9a1b2c3d-...")
    @NotBlank(message = "previewId 不能为空")
    @Size(max = 36, message = "previewId 格式不对")
    private String previewId;
}
