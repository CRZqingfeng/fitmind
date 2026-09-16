package com.fitmind.media.controller;

import com.fitmind.media.domain.dto.FileDTO;
import com.fitmind.media.enums.BizType;
import com.fitmind.media.service.IFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件接口（图片等通用文件）。
 *
 * <p>图片体积不大，走服务端中转即可；视频走 {@link MediaController} 的预签名直传。</p>
 */
@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
@Tag(name = "文件管理相关接口")
public class FileController {

    private final IFileService fileService;

    @Operation(summary = "上传文件")
    @PostMapping
    public FileDTO uploadFile(
            @Parameter(description = "文件数据") @RequestParam("file") MultipartFile file,
            @Parameter(description = "业务类型：avatar/course-cover/action-cover/media-cover/common")
            @RequestParam(value = "bizType", required = false) String bizType) {
        return fileService.uploadFile(file, BizType.of(bizType));
    }

    @Operation(summary = "获取文件信息（返回带签名的可访问地址）")
    @GetMapping("/{id}")
    public FileDTO getFileInfo(
            @Parameter(description = "文件id", example = "1") @PathVariable("id") Long id) {
        return fileService.getFileInfo(id);
    }

    @Operation(summary = "删除文件")
    @DeleteMapping("/{id}")
    public void deleteFileById(
            @Parameter(description = "文件id", example = "1") @PathVariable("id") Long id) {
        fileService.deleteFileById(id);
    }
}
