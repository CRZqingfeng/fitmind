package com.fitmind.media.controller;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.media.domain.dto.ConfirmMediaDTO;
import com.fitmind.media.domain.dto.MediaDTO;
import com.fitmind.media.domain.dto.UploadSignatureDTO;
import com.fitmind.media.domain.query.MediaQuery;
import com.fitmind.media.domain.vo.MediaVO;
import com.fitmind.media.domain.vo.PlayUrlVO;
import com.fitmind.media.service.IMediaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 媒资接口（训练视频）。
 *
 * <p>上传分三步：申请签名 → 前端直传 MinIO → 确认。
 * 视频不经过本服务，省带宽也省内存。</p>
 */
@RestController
@RequestMapping("/medias")
@RequiredArgsConstructor
@Tag(name = "媒资管理相关接口")
public class MediaController {

    private final IMediaService mediaService;

    @Operation(summary = "分页查询媒资")
    @GetMapping
    public PageDTO<MediaVO> queryMediaPage(MediaQuery query) {
        return mediaService.queryMediaPage(query);
    }

    @Operation(summary = "申请上传签名（第1步），返回的 uploadUrl 供前端直传 MinIO")
    @PostMapping("/upload-signature")
    public UploadSignatureDTO applyUploadSignature(
            @Parameter(description = "视频原始文件名", example = "深蹲标准动作.mp4")
            @RequestParam("filename") String filename,
            @Parameter(description = "文件大小，单位字节", example = "10485760")
            @RequestParam(value = "size", required = false, defaultValue = "0") Long size) {
        return mediaService.applyUploadSignature(filename, size == null ? 0L : size);
    }

    @Operation(summary = "确认上传（第3步），服务端会向 MinIO 核实对象是否真的存在")
    @PostMapping("/{id}/confirm")
    public MediaDTO confirmUpload(
            @Parameter(description = "媒资id", example = "1") @PathVariable("id") Long id,
            @Valid @RequestBody ConfirmMediaDTO dto) {
        return mediaService.confirmUpload(id, dto);
    }

    @Operation(summary = "获取播放地址（带签名，会过期）")
    @GetMapping("/{id}/play-url")
    public PlayUrlVO getPlayUrl(
            @Parameter(description = "媒资id", example = "1") @PathVariable("id") Long id) {
        return mediaService.getPlayUrl(id);
    }

    @Operation(summary = "管理端预览地址（有效期更短）")
    @GetMapping("/{id}/preview-url")
    public PlayUrlVO getPreviewUrl(
            @Parameter(description = "媒资id", example = "1") @PathVariable("id") Long id) {
        return mediaService.getPreviewUrl(id);
    }

    @Operation(summary = "删除媒资（被课程引用时拒绝）")
    @DeleteMapping("/{id}")
    public void deleteMedia(
            @Parameter(description = "媒资id", example = "1") @PathVariable("id") Long id) {
        mediaService.deleteMedia(id);
    }

    @Operation(summary = "批量删除媒资")
    @DeleteMapping
    public void deleteMedias(
            @Parameter(description = "媒资id集合，例如1,2,3") @RequestParam("ids") List<Long> ids) {
        mediaService.deleteMedias(ids);
    }

    @Operation(summary = "【内部调用】按id查询媒资，供课程模块校验 videoId")
    @GetMapping("/internal/{id}")
    public MediaDTO getMediaById(
            @Parameter(description = "媒资id", example = "1") @PathVariable("id") Long id) {
        return mediaService.getMediaById(id);
    }
}
