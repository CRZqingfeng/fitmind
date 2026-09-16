package com.fitmind.media.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.media.domain.dto.FileDTO;
import com.fitmind.media.domain.po.File;
import com.fitmind.media.enums.BizType;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件服务（图片等通用文件）。
 */
public interface IFileService extends IService<File> {

    /**
     * 上传文件。
     *
     * <p>图片体积不大，走服务端中转完全可接受，实现也最简单
     * （视频则走「预签名直传」，见 {@link IMediaService#applyUploadSignature}）。</p>
     *
     * @param file    文件
     * @param bizType 业务类型，决定存到哪个目录
     */
    FileDTO uploadFile(MultipartFile file, BizType bizType);

    /** 查询文件信息（返回带签名的可访问地址） */
    FileDTO getFileInfo(Long id);

    /** 删除文件：删记录 + 删对象存储里的文件 */
    void deleteFileById(Long id);
}
