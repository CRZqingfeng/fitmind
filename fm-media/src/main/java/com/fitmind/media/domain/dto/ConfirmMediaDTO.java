package com.fitmind.media.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 确认上传请求。
 *
 * <p>为什么需要这一步：只申请签名的话，服务端无从得知前端到底传没传成功
 * （用户可能拿到 URL 就关了页面），库里会堆一堆「上传中」的僵尸记录。</p>
 *
 * <p>所以由服务端主动向 MinIO 核实对象存在，并把真实大小写回；
 * 时长由前端用 {@code <video>} 标签读到后提交。</p>
 */
@Data
@Schema(description = "确认上传请求")
public class ConfirmMediaDTO {

    @Schema(description = "视频时长，单位秒（前端读取 video 标签得到）", example = "125", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "视频时长不能为空")
    private Integer duration;

    @Schema(description = "封面地址，一般先调用 /files 上传图片再回传其 url")
    private String coverUrl;
}
