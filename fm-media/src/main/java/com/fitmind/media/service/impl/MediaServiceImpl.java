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
import com.fitmind.media.mapper.MediaMapper;
import com.fitmind.media.service.IMediaService;
import com.fitmind.media.storage.IFileStorage;
import com.fitmind.media.storage.IMediaStorage;
import com.fitmind.media.storage.MinioProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final MinioProperties properties;

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
            vo.setCoverUrl(fileStorage.getAccessUrlByRawUrl(po.getCoverUrl(), properties.getPlayExpire()));
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
        dto.setUploadUrl(mediaStorage.getUploadUrl(key, properties.getUploadExpire(), size));
        dto.setExpire(properties.getUploadExpire());
        log.debug("生成上传签名：mediaId={}, key={}", media.getId(), key);
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
        String url = mediaStorage.getPlayUrl(media.getFileId(), properties.getPlayExpire());
        return PlayUrlVO.of(url, properties.getPlayExpire(), media.getDuration());
    }

    @Override
    public PlayUrlVO getPreviewUrl(Long id) {
        Media media = getById(id);
        if (media == null) {
            throw new BizIllegalException(MediaErrorInfo.Msg.MEDIA_NOT_EXISTS);
        }
        String url = mediaStorage.getPlayUrl(media.getFileId(), properties.getPreviewExpire());
        return PlayUrlVO.of(url, properties.getPreviewExpire(), media.getDuration());
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
        mediaStorage.deleteFile(media.getFileId());
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
        mediaStorage.deleteFiles(list.stream().map(Media::getFileId).toList());
        removeByIds(ids);
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
