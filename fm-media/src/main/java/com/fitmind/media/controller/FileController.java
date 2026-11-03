package com.fitmind.media.controller;

import com.fitmind.media.domain.dto.FileDTO;
import com.fitmind.media.domain.dto.InitUploadDTO;
import com.fitmind.media.domain.vo.InitUploadVO;
import com.fitmind.media.domain.vo.PartUploadVO;
import com.fitmind.media.enums.BizType;
import com.fitmind.media.service.IFileService;
import com.fitmind.media.service.IUploadTaskService;
import com.fitmind.common.utils.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件接口（图片等通用文件）。
 *
 * <p>图片体积不大，走服务端中转即可；视频走 {@link MediaController} 的预签名直传。</p>
 *
 * <p>M12-1 起多了一组「分片上传」接口（{@code /files/uploads/**}）：
 * 面向大文件（动作考核视频、课程素材），支持断点续传与秒传。
 * 小文件继续走 {@code POST /files}，两条路径互不影响。</p>
 */
@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
@Tag(name = "文件管理相关接口")
public class FileController {

    private final IFileService fileService;

    private final IUploadTaskService uploadTaskService;

    @Operation(summary = "上传文件")
    @PostMapping
    public FileDTO uploadFile(
            @Parameter(description = "文件数据") @RequestParam("file") MultipartFile file,
            @Parameter(description = "业务类型：avatar/course-cover/action-cover/media-cover/common")
            @RequestParam(value = "bizType", required = false) String bizType) {
        return fileService.uploadFile(file, BizType.of(bizType));
    }

    // ==================== M12-1 分片上传（断点续传 + 秒传） ====================

    @Operation(summary = "① 初始化分片上传：命中秒传则直接返回文件，否则返回任务与已传分片")
    @PostMapping("/uploads")
    public InitUploadVO initUpload(@Valid @RequestBody InitUploadDTO dto) {
        return uploadTaskService.init(dto, UserContext.getUser());
    }

    @Operation(summary = "② 查询上传进度（刷新页面后恢复用，也可直接用同一 hash 再调 ①）")
    @GetMapping("/uploads/{taskId}")
    public InitUploadVO uploadProgress(
            @Parameter(description = "上传任务id") @PathVariable("taskId") Long taskId) {
        return uploadTaskService.progress(taskId, UserContext.getUser());
    }

    @Operation(summary = "③ 上传一个分片（同一片重复传是幂等的）")
    @PutMapping(value = "/uploads/{taskId}/parts/{partNumber}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PartUploadVO uploadPart(
            @Parameter(description = "上传任务id") @PathVariable("taskId") Long taskId,
            @Parameter(description = "分片序号，从 1 开始") @PathVariable("partNumber") int partNumber,
            @Parameter(description = "分片数据") @RequestPart("chunk") MultipartFile chunk) {
        return uploadTaskService.uploadPart(taskId, partNumber, chunk, UserContext.getUser());
    }

    @Operation(summary = "④ 合并分片并生成文件记录（缺片会被拒绝，不会尽力合并）")
    @PostMapping("/uploads/{taskId}/complete")
    public FileDTO completeUpload(
            @Parameter(description = "上传任务id") @PathVariable("taskId") Long taskId) {
        return uploadTaskService.complete(taskId, UserContext.getUser());
    }

    @Operation(summary = "⑤ 取消上传，清理已上传的分片")
    @DeleteMapping("/uploads/{taskId}")
    public void abortUpload(
            @Parameter(description = "上传任务id") @PathVariable("taskId") Long taskId) {
        uploadTaskService.abort(taskId, UserContext.getUser());
    }

    @Operation(summary = "获取文件信息（返回带签名的可访问地址）")
    @GetMapping("/{id}")
    public FileDTO getFileInfo(
            @Parameter(description = "文件id", example = "1") @PathVariable("id") Long id) {
        return fileService.getFileInfo(id);
    }

    @Operation(summary = "删除文件（共享对象按引用计数，最后一个引用才真删）")
    @DeleteMapping("/{id}")
    public void deleteFileById(
            @Parameter(description = "文件id", example = "1") @PathVariable("id") Long id) {
        fileService.deleteFileById(id);
    }
}
