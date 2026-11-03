package com.fitmind.media.domain.vo;

import com.fitmind.media.domain.dto.FileDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 初始化 / 查询进度的返回。
 *
 * <p>{@code instant = true} 时只有 {@code file} 有意义（秒传，一个字节都不用传）；
 * 否则看 {@code taskId + uploadedChunks}，客户端只补传缺的片。</p>
 */
@Data
@Accessors(chain = true)
@Schema(description = "分片上传初始化结果")
public class InitUploadVO {

    @Schema(description = "是否秒传命中（true 时无需上传任何字节）")
    private Boolean instant;

    @Schema(description = "上传任务 id；秒传时为 null")
    private Long taskId;

    @Schema(description = "秒传命中时返回的现成文件")
    private FileDTO file;

    /**
     * 媒资 id（仅 {@code target=media} 时有值）。
     *
     * <p>前端拿到它就能直接走现有的播放/引用逻辑（{@code GET /medias/{id}/play-url}）。</p>
     */
    @Schema(description = "视频媒资 id（target=media 时返回）")
    private Long mediaId;

    @Schema(description = "目标对象 key（由 hash 派生，可预测）")
    private String objectKey;

    @Schema(description = "★ 分片大小以服务端返回为准：客户端换了分片大小会导致序号对不上，续传必错")
    private Integer chunkSize;

    @Schema(description = "分片总数")
    private Integer chunkTotal;

    @Schema(description = "已成功上传的分片序号（断点续传只需补传不在其中的）")
    private List<Integer> uploadedChunks;

    @Schema(description = "已上传字节数")
    private Long uploadedBytes;

    @Schema(description = "已上传百分比（0-100，向下取整）")
    private Integer uploadedPercent;
}
