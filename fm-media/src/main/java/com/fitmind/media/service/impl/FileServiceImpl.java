package com.fitmind.media.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.utils.UserContext;
import com.fitmind.media.constants.MediaErrorInfo;
import com.fitmind.media.domain.dto.FileDTO;
import com.fitmind.media.domain.po.File;
import com.fitmind.media.enums.BizType;
import com.fitmind.media.enums.FileStatus;
import com.fitmind.media.mapper.FileMapper;
import com.fitmind.media.service.IFileService;
import com.fitmind.media.storage.IFileStorage;
import com.fitmind.media.storage.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 文件服务实现（图片等通用文件）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl extends ServiceImpl<FileMapper, File> implements IFileService {

    private final IFileStorage fileStorage;

    /**
     * ★ 注意这里注入的是 {@link StorageProperties}（通用）而不是 MinioProperties（厂商专属）——
     * 本类只用到"签名有效期"这一个与厂商无关的配置。若注入 MinioProperties，
     * 切到 OSS 后该 Bean 不存在，服务会**启动失败**。
     */
    private final StorageProperties storageProperties;

    @Override
    public FileDTO uploadFile(MultipartFile file, BizType bizType) {
        // 1. 原始文件名
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new BizIllegalException(MediaErrorInfo.Msg.FILE_KEY_IS_NULL);
        }
        // 2. 生成对象 key：业务目录 / 年月 / uuid.后缀
        String key = generateKey(originalFilename, bizType);
        long size = file.getSize();

        // 3. 上传到对象存储
        try (InputStream is = file.getInputStream()) {
            fileStorage.uploadFile(key, is, size);
        } catch (IOException e) {
            log.error("读取上传文件失败，filename={}", originalFilename, e);
            throw new BizIllegalException(MediaErrorInfo.Msg.FILE_UPLOAD_ERROR);
        }

        // 4. 入库
        File po = new File()
                .setKey(key)
                .setFilename(originalFilename)
                .setBizType(bizType)
                .setSize(size)
                .setStatus(FileStatus.UPLOADED)
                .setUrl(fileStorage.getRawUrl(key))
                .setCreater(UserContext.getUser())
                .setDeleted(0);
        try {
            save(po);
        } catch (Exception e) {
            // 入库失败要把已上传的对象删掉，避免产生无主的孤儿文件
            log.error("文件信息入库失败，已回滚对象存储文件，key={}", key, e);
            fileStorage.deleteFile(key);
            throw new BizIllegalException(MediaErrorInfo.Msg.FILE_UPLOAD_ERROR);
        }

        // 5. 返回（url 带签名，前端可直接展示）
        FileDTO dto = new FileDTO();
        dto.setId(po.getId());
        dto.setFilename(originalFilename);
        dto.setSize(size);
        dto.setRawUrl(fileStorage.getRawUrl(key));
        dto.setUrl(fileStorage.getAccessUrl(key, storageProperties.getPlayExpire()));
        return dto;
    }

    @Override
    public FileDTO getFileInfo(Long id) {
        File po = getById(id);
        if (po == null) {
            throw new BizIllegalException(MediaErrorInfo.Msg.FILE_NOT_EXISTS);
        }
        FileDTO dto = new FileDTO();
        dto.setId(po.getId());
        dto.setFilename(po.getFilename());
        dto.setSize(po.getSize());
        dto.setRawUrl(po.getUrl());
        dto.setUrl(fileStorage.getAccessUrl(po.getKey(), storageProperties.getPlayExpire()));
        return dto;
    }

    @Override
    public void deleteFileById(Long id) {
        File po = getById(id);
        if (po == null) {
            return;
        }
        fileStorage.deleteFile(po.getKey());
        removeById(id);
    }

    /**
     * 生成对象 key：{业务目录}/{yyyy/MM}/{uuid}.{后缀}
     *
     * <p>保留原文件后缀，对象存储才能返回正确的 Content-Type；
     * 用 uuid 而非原文件名，避免重名覆盖和中文名编码问题。</p>
     */
    private String generateKey(String originalFilename, BizType bizType) {
        String suffix = "";
        int idx = originalFilename.lastIndexOf('.');
        if (idx > 0) {
            suffix = originalFilename.substring(idx); // 含点号，如 ".jpg"
        }
        String dir = bizType == null ? BizType.COMMON.getPath() : bizType.getPath();
        return dir + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM")) + "/"
                + UUID.randomUUID().toString().replace("-", "") + suffix;
    }
}
