package com.fitmind.media.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 文件信息。
 *
 * <p>{@code url} 是<b>带签名的、可直接访问</b>的地址（有有效期）——
 * 因为 bucket 是私有的，不带签名的地址浏览器打不开。
 * {@code rawUrl} 是不带签名的原始地址，仅用于记录在案。</p>
 */
@Data
@Schema(description = "文件信息")
public class FileDTO {

    @Schema(description = "文件id")
    private Long id;

    @Schema(description = "原始文件名")
    private String filename;

    @Schema(description = "带签名的访问地址，可直接打开，注意有有效期")
    private String url;

    @Schema(description = "不带签名的原始地址")
    private String rawUrl;

    @Schema(description = "文件大小，单位字节")
    private Long size;

    /**
     * 视频媒资 id（M12-1，仅分片上传且 {@code target=media} 时有值）。
     *
     * <p>放在 FileDTO 里而不是另建一个 VO：前端拿到的"上传结果"就是这几项，
     * 分成两个类型会让前端写两套判断；而 mediaId 为 null 时天然表示"这是个普通文件"。</p>
     */
    @Schema(description = "视频媒资 id（target=media 时返回，否则为 null）")
    private Long mediaId;
}
