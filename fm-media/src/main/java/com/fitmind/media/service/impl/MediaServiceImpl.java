package com.fitmind.media.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.utils.UserContext;
import com.fitmind.media.constants.MediaErrorInfo;
import com.fitmind.media.domain.dto.ConfirmMediaDTO;
import com.fitmind.media.domain.dto.MediaDTO;
import com.fitmind.media.domain.dto.UploadSignatureDTO;
import com.fitmind.media.domain.po.Media;
import com.fitmind.media.domain.query.MediaQuery;
import com.fitmind.media.domain.vo.MediaVO;
import com.fitmind.media.domain.vo.PlayUrlVO;
import com.fitmind.media.enums.FileStatus;
import com.fitmind.media.mapper.FileObjectMapper;
import com.fitmind.media.mapper.MediaMapper;
import com.fitmind.media.service.IMediaService;
import com.fitmind.media.storage.IFileStorage;
import com.fitmind.media.storage.IMediaStorage;
import com.fitmind.media.storage.StorageProperties;
import com.fitmind.media.utils.ContentTypeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 媒资服务实现（训练视频）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaServiceImpl extends ServiceImpl<MediaMapper, Media> implements IMediaService {

    private final IMediaStorage mediaStorage;
    private final IFileStorage fileStorage;

    /** M12-1：秒传产生的媒资与别人共享字节，删除时要维护引用计数 */
    private final FileObjectMapper fileObjectMapper;

    /**
     * ★ 与 {@code FileServiceImpl} 同理：只用到"签名有效期"这个与厂商无关的配置，
     * 所以注入 {@link StorageProperties} 而不是 MinioProperties。
     */
    private final StorageProperties storageProperties;

    @Override
    public PageDTO<MediaVO> queryMediaPage(MediaQuery query) {
        // 1. 分页查询
        LambdaQueryWrapper<Media> wrapper = new LambdaQueryWrapper<Media>()
                .like(query.getName() != null && !query.getName().isBlank(), Media::getFilename, query.getName())
                .eq(query.getStatus() != null, Media::getStatus, query.getStatus());
        var page = page(query.toMpPageDefaultSortByCreateTimeDesc(), wrapper);

        // 2. PO -> VO，顺手把封面换成可直接显示的签名地址
        return PageDTO.of(page, po -> {
            MediaVO vo = new MediaVO();
            vo.setId(po.getId());
            vo.setFilename(po.getFilename());
            vo.setCoverUrl(fileStorage.getAccessUrlByRawUrl(po.getCoverUrl(), storageProperties.getPlayExpire()));
            vo.setDuration(po.getDuration());
            vo.setSize(po.getSize());
            vo.setUseTimes(po.getUseTimes());
            vo.setStatus(po.getStatus());
            vo.setCreateTime(po.getCreateTime());
            vo.setCreater(po.getCreater());
            return vo;
        });
    }

    @Override
    public UploadSignatureDTO applyUploadSignature(String filename, long size) {
        if (filename == null || filename.isBlank()) {
            throw new BizIllegalException(MediaErrorInfo.Msg.MEDIA_FILENAME_IS_NULL);
        }
        // 1. 生成对象 key
        String key = generateKey(filename);
        // 2. 预创建记录（上传中）
        Media media = new Media()
                .setFileId(key)
                .setFilename(filename)
                .setStatus(FileStatus.UPLOADING)
                .setMediaUrl(fileStorage.getRawUrl(key))
                .setCreater(UserContext.getUser())
                .setDeleted(0);
        save(media);

        // 3. 生成预签名 PUT 地址
        UploadSignatureDTO dto = new UploadSignatureDTO();
        dto.setMediaId(media.getId());
        dto.setKey(key);
        dto.setUploadUrl(mediaStorage.getUploadUrl(key, storageProperties.getUploadExpire(), size));
        dto.setExpire(storageProperties.getUploadExpire());
        /**
         * ★★ 只有 OSS 需要回传 Content-Type：它的 V4 签名把 Content-Type 算进去了，
         *   而浏览器直传一定会带这个头 —— 值不一致就是 403 SignatureDoesNotMatch。
         *   MinIO 不签它，此处保持 null，前端沿用"自己按扩展名猜"的老行为，**行为不变**。
         *   算值走 ContentTypeUtil，与 OssMediaStorage 签名时用的是同一个来源。
         */
        if (mediaStorage.requiresContentTypeOnUpload()) {
            dto.setContentType(ContentTypeUtil.ofName(filename));
        }
        log.debug("生成上传签名：mediaId={}, key={}, contentType={}", media.getId(), key, dto.getContentType());
        return dto;
    }

    @Override
    @Transactional
    public MediaDTO confirmUpload(Long id, ConfirmMediaDTO dto) {
        Media media = getById(id);
        if (media == null) {
            throw new BizIllegalException(MediaErrorInfo.Msg.MEDIA_NOT_EXISTS);
        }

        // 1. 向对象存储核实：对象是否真的存在、真实大小是多少
        Long realSize = mediaStorage.statFile(media.getFileId());
        if (realSize == null) {
            // 前端还没传，或传到了别的地方
            throw new BizIllegalException(MediaErrorInfo.Msg.MEDIA_NOT_UPLOADED);
        }

        // 2. 更新状态（大小以对象存储返回为准，不信任前端传值）
        Media update = new Media()
                .setId(id)
                .setStatus(FileStatus.UPLOADED)
                .setSize(realSize)
                .setDuration(dto.getDuration())
                .setUpdater(UserContext.getUser());
        if (dto.getCoverUrl() != null && !dto.getCoverUrl().isBlank()) {
            update.setCoverUrl(dto.getCoverUrl());
        }
        updateById(update);

        return toDTO(getById(id));
    }

    @Override
    public PlayUrlVO getPlayUrl(Long id) {
        Media media = getById(id);
        if (media == null) {
            throw new BizIllegalException(MediaErrorInfo.Msg.MEDIA_NOT_EXISTS);
        }
        String url = mediaStorage.getPlayUrl(media.getFileId(), storageProperties.getPlayExpire());
        return PlayUrlVO.of(url, storageProperties.getPlayExpire(), media.getDuration());
    }

    @Override
    public PlayUrlVO getPreviewUrl(Long id) {
        Media media = getById(id);
        if (media == null) {
            throw new BizIllegalException(MediaErrorInfo.Msg.MEDIA_NOT_EXISTS);
        }
        String url = mediaStorage.getPlayUrl(media.getFileId(), storageProperties.getPreviewExpire());
        return PlayUrlVO.of(url, storageProperties.getPreviewExpire(), media.getDuration());
    }

    @Override
    @Transactional
    public void deleteMedia(Long id) {
        Media media = getById(id);
        if (media == null) {
            return;
        }
        if (media.getUseTimes() != null && media.getUseTimes() > 0) {
            throw new BizIllegalException(MediaErrorInfo.Msg.MEDIA_IN_USE);
        }
        releaseObject(media);
        removeById(id);
    }

    @Override
    @Transactional
    public void deleteMedias(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<Media> list = listByIds(ids);
        if (list.isEmpty()) {
            return;
        }
        // 被引用的一律拒绝，整批失败
        boolean inUse = list.stream().anyMatch(m -> m.getUseTimes() != null && m.getUseTimes() > 0);
        if (inUse) {
            throw new BizIllegalException(MediaErrorInfo.Msg.MEDIA_IN_USE);
        }
        // ★ 逐条按引用计数释放，不能像以前那样"把 keys 一起删掉"：
        //   秒传产生的两条媒资共用一个 key，批量删会把别人的视频一起删了。
        list.forEach(this::releaseObject);
        removeByIds(ids);
    }

    /**
     * 释放一条媒资占用的物理对象。
     *
     * <p>老数据（{@code objectId} 为空）：key 独占，直接删对象。</p>
     * <p>M12-1 之后：先减引用，归零才删行、删行成功才删字节 ——
     * 顺序与理由见 {@code FileServiceImpl#deleteFileById} 的注释。</p>
     */
    private void releaseObject(Media media) {
        if (media.getObjectId() == null) {
            mediaStorage.deleteFile(media.getFileId());
            return;
        }
        int affected = fileObjectMapper.decreaseRef(media.getObjectId());
        if (affected == 0) {
            log.warn("物理对象 {} 的引用计数已为 0，跳过递减（可能是重复删除）", media.getObjectId());
        } else if (fileObjectMapper.deleteIfUnused(media.getObjectId()) > 0) {
            // ★ 事务里不做网络 IO：提交成功后再删字节（理由见 FileServiceImpl#deleteObjectAfterCommit）
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                String key = media.getFileId();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        mediaStorage.deleteFile(key);
                    }
                });
            } else {
                mediaStorage.deleteFile(media.getFileId());
            }
            log.info("物理对象 {} 已无引用，删除视频对象 {}", media.getObjectId(), media.getFileId());
        }
    }

    @Override
    public MediaDTO getMediaById(Long id) {
        Media media = getById(id);
        if (media == null) {
            return null;
        }
        return toDTO(media);
    }

    private MediaDTO toDTO(Media po) {
        MediaDTO dto = new MediaDTO();
        dto.setId(po.getId());
        dto.setFilename(po.getFilename());
        dto.setFileId(po.getFileId());
        dto.setDuration(po.getDuration());
        dto.setSize(po.getSize());
        dto.setCoverUrl(po.getCoverUrl());
        dto.setStatus(po.getStatus());
        return dto;
    }

    /** 生成 key：media/{yyyy/MM}/{uuid}.{后缀} */
    private String generateKey(String filename) {
        String suffix = "";
        int idx = filename.lastIndexOf('.');
        if (idx > 0) {
            suffix = filename.substring(idx);
        }
        return "media/" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM")) + "/"
                + UUID.randomUUID().toString().replace("-", "") + suffix;
    }
}
