package com.fitmind.media.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.media.constants.MediaErrorInfo;
import com.fitmind.media.domain.dto.FileDTO;
import com.fitmind.media.domain.dto.InitUploadDTO;
import com.fitmind.media.domain.po.File;
import com.fitmind.media.domain.po.FileObject;
import com.fitmind.media.domain.po.FileUploadPart;
import com.fitmind.media.domain.po.FileUploadTask;
import com.fitmind.media.domain.po.Media;
import com.fitmind.media.domain.vo.InitUploadVO;
import com.fitmind.media.domain.vo.PartUploadVO;
import com.fitmind.media.enums.BizType;
import com.fitmind.media.enums.FileStatus;
import com.fitmind.media.enums.UploadStatus;
import com.fitmind.media.mapper.FileMapper;
import com.fitmind.media.mapper.FileObjectMapper;
import com.fitmind.media.mapper.FileUploadPartMapper;
import com.fitmind.media.mapper.FileUploadTaskMapper;
import com.fitmind.media.mapper.MediaMapper;
import com.fitmind.media.service.IUploadTaskService;
import com.fitmind.media.storage.IFileStorage;
import com.fitmind.media.storage.StorageProperties;
import com.fitmind.media.utils.ContentTypeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 分片上传：断点续传 + 秒传。
 *
 * <p><b>为什么三段式而不是一个接口：</b>大文件的上传过程一定会失败（网络、切后台、关页面）。
 * 一次请求的模式下，"失败"意味着全部重来；三段式把过程拆成可重放的步骤，
 * 失败只是"少了几片"，而少几片是可以问出来的（{@link #progress}）。</p>
 *
 * <p><b>两个容易搞混的"状态"：</b></p>
 * <ul>
 *   <li>{@code file_upload_part} 的<b>快照</b>：用来快速算进度，可能滞后；</li>
 *   <li>对象存储里真实的<b>分片对象</b>：合并前必须逐个核实，它才是真相。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadTaskServiceImpl extends ServiceImpl<FileUploadTaskMapper, FileUploadTask>
        implements IUploadTaskService {

    /**
     * 单片最小 5MiB —— 不是我们定的，是 S3/MinIO 的硬约束：
     * 除最后一片外，每片必须 ≥ 5MiB，否则合并时报 {@code EntityTooSmall}，
     * 而这个错**只在合并那一刻**才出现，前面 40 片全都"成功"。
     */
    private static final int MIN_CHUNK_SIZE = 5 * 1024 * 1024;

    /** S3 单次合并最多 1 万片 —— 超过就得放大分片，而不是报错让用户改文件 */
    private static final int MAX_CHUNK_TOTAL = 10000;

    /** 本模块只服务图片/短视频，5GiB 足够；再大要走专门的媒资通道 */
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024 * 1024;

    /**
     * 分片临时对象前缀。
     *
     * <p>★ 刻意**不**放进桶的匿名读白名单 ——
     * 传了一半的分片不应该被任何人直接访问（白名单见 scripts/setup-minio-anonymous.py）。</p>
     */
    private static final String CHUNK_PREFIX = "chunks/";

    /** 产出类型：通用文件 */
    private static final String TARGET_FILE = "file";

    /** 产出类型：视频媒资（额外落一条 media 记录，供播放/引用） */
    private static final String TARGET_MEDIA = "media";

    private final FileUploadPartMapper partMapper;

    private final FileMapper fileMapper;

    private final FileObjectMapper fileObjectMapper;

    private final MediaMapper mediaMapper;

    private final IFileStorage fileStorage;

    private final StorageProperties storageProperties;

    // ==================== ① 初始化 / 秒传 / 断点 ====================

    @Override
    @Transactional
    public InitUploadVO init(InitUploadDTO dto, Long userId) {
        if (dto.getSize() > MAX_FILE_SIZE) {
            throw new BizIllegalException("文件超过 5GiB，请改用媒资直传通道");
        }
        if (!fileStorage.supportsMultipart()) {
            throw new BizIllegalException(MediaErrorInfo.Msg.MULTIPART_NOT_SUPPORTED);
        }
        BizType bizType = BizType.of(dto.getBizType());
        String target = normalizeTarget(dto.getTarget());
        String hash = dto.getHash().toLowerCase(Locale.ROOT);
        long size = dto.getSize();

        // ---------- ① 秒传：内容已经存在，一个字节都不用传 ----------
        FileObject object = findObject(bizType, hash);
        if (object != null
                && object.getSize() != null && object.getSize() == size
                && canInstantUpload(object, bizType, userId)
                // ★ 必须向存储核实对象真的在：库里有、对象被生命周期规则清掉的情况是存在的。
                //   不核实就会出现"秒传成功但打不开"，而用户完全不知道发生了什么。
                && fileStorage.exists(object.getObjectKey())) {
            File file = createFileRecord(object, dto.getFilename(), bizType, userId);
            fileObjectMapper.increaseRef(object.getId());
            Long mediaId = TARGET_MEDIA.equals(target)
                    ? createMediaRecord(object, dto.getFilename(), userId).getId()
                    : null;
            log.info("[秒传] 命中 hash={} bizType={} target={} objectId={} → fileId={} mediaId={}",
                    hash, bizType.getValue(), target, object.getId(), file.getId(), mediaId);
            return new InitUploadVO()
                    .setInstant(true)
                    .setFile(toFileDTO(file))
                    .setMediaId(mediaId);
        }

        // ---------- ② 断点：同一份文件还有没传完的任务，就接着用 ----------
        FileUploadTask task = getOne(new LambdaQueryWrapper<FileUploadTask>()
                .eq(FileUploadTask::getFileHash, hash)
                .eq(FileUploadTask::getBizType, bizType.getValue())
                .eq(FileUploadTask::getSize, size)
                .eq(FileUploadTask::getCreater, userId)
                .eq(FileUploadTask::getStatus, UploadStatus.UPLOADING.getValue())
                .orderByDesc(FileUploadTask::getId)
                .last("LIMIT 1"));

        if (task != null) {
            log.info("[断点] 复用任务 taskId={}，已传 {}/{} 片",
                    task.getId(), task.getUploadedChunks(), task.getChunkTotal());
            return buildProgress(task);
        }

        // ---------- ③ 新建任务 ----------
        int chunkSize = normalizeChunkSize(dto.getChunkSize(), size);
        int chunkTotal = (int) ((size + chunkSize - 1) / chunkSize);
        String objectKey = buildObjectKey(bizType, hash, dto.getFilename());
        task = new FileUploadTask()
                .setFileHash(hash)
                .setBizType(bizType.getValue())
                .setTarget(target)
                .setFilename(dto.getFilename())
                .setSize(size)
                .setChunkSize(chunkSize)
                .setChunkTotal(chunkTotal)
                .setObjectKey(objectKey)
                .setStatus(UploadStatus.UPLOADING)
                .setUploadedChunks(0)
                .setUploadedBytes(0L)
                .setCreater(userId)
                .setDeleted(0);
        save(task);
        log.info("[分片] 新建任务 taskId={} key={} {}/{} 片", task.getId(), objectKey, chunkTotal, chunkSize);
        return buildProgress(task);
    }

    @Override
    public InitUploadVO progress(Long taskId, Long userId) {
        return buildProgress(requireOwnedTask(taskId, userId));
    }

    // ==================== ② 上传一片 ====================

    @Override
    @Transactional
    public PartUploadVO uploadPart(Long taskId, int partNumber, MultipartFile chunk, Long userId) {
        FileUploadTask task = requireOwnedTask(taskId, userId);
        if (task.getStatus() != UploadStatus.UPLOADING) {
            throw new BizIllegalException(MediaErrorInfo.Msg.UPLOAD_TASK_NOT_UPLOADING);
        }
        if (partNumber < 1 || partNumber > task.getChunkTotal()) {
            throw new BizIllegalException("分片序号越界：应在 1~" + task.getChunkTotal() + " 之间");
        }
        if (chunk == null || chunk.isEmpty()) {
            throw new BizIllegalException("分片内容为空");
        }
        long expected = expectedPartSize(task, partNumber);
        if (chunk.getSize() != expected) {
            // ★ 大小必须严格相等：多一字节少一字节都会让合并后的对象与声明的 size 对不上，
            //   而"对不上"在合并时表现为一个莫名其妙的错误，在这里表现为一句人话。
            throw new BizIllegalException(String.format(
                    "第 %d 片大小不符：应为 %d 字节，实际 %d 字节", partNumber, expected, chunk.getSize()));
        }

        String chunkKey = chunkKey(taskId, partNumber);
        try (InputStream is = chunk.getInputStream()) {
            // ★ 带 Content-Type：合并后的对象沿用分片元数据，不带就会变成 octet-stream
            fileStorage.uploadFile(chunkKey, is, chunk.getSize(), ContentTypeUtil.ofName(task.getFilename()));
        } catch (IOException e) {
            log.error("读取分片失败，taskId={}, part={}", taskId, partNumber, e);
            throw new BizIllegalException(MediaErrorInfo.Msg.FILE_UPLOAD_ERROR);
        }

        // 幂等写入（同一片重传 = 覆盖），然后**重算**快照
        partMapper.upsert(new FileUploadPart()
                .setTaskId(taskId)
                .setPartNumber(partNumber)
                .setSize(chunk.getSize()));
        int count = partMapper.countByTask(taskId);
        long bytes = partMapper.sumSizeByTask(taskId);
        updateById(new FileUploadTask().setId(taskId).setUploadedChunks(count).setUploadedBytes(bytes));

        log.debug("[分片] taskId={} part={}/{} 完成，进度 {}/{}",
                taskId, partNumber, task.getChunkTotal(), count, task.getChunkTotal());
        return new PartUploadVO()
                .setPartNumber(partNumber)
                .setChunkTotal(task.getChunkTotal())
                .setUploadedChunks(listPartNumbers(taskId))
                .setUploadedBytes(bytes)
                .setUploadedPercent(percent(bytes, task.getSize()));
    }

    // ==================== ③ 合并 ====================

    @Override
    @Transactional
    public FileDTO complete(Long taskId, Long userId) {
        FileUploadTask task = requireOwnedTask(taskId, userId);

        // 幂等：重复 complete 返回上次的结果，而不是报错（前端重试很常见）
        if (task.getStatus() == UploadStatus.COMPLETED) {
            FileObject object = findObject(BizType.of(task.getBizType()), task.getFileHash());
            File exist = object == null ? null : fileMapper.selectOne(new LambdaQueryWrapper<File>()
                    .eq(File::getObjectId, object.getId())
                    .eq(File::getCreater, userId)
                    .orderByDesc(File::getId)
                    .last("LIMIT 1"));
            if (exist != null) {
                FileDTO dto = toFileDTO(exist);
                Media media = mediaMapper.selectOne(new LambdaQueryWrapper<Media>()
                        .eq(Media::getObjectId, object.getId())
                        .eq(Media::getCreater, userId)
                        .orderByDesc(Media::getId)
                        .last("LIMIT 1"));
                if (media != null) {
                    dto.setMediaId(media.getId());
                }
                return dto;
            }
            throw new BizIllegalException(MediaErrorInfo.Msg.UPLOAD_TASK_COMPLETED);
        }
        if (task.getStatus() != UploadStatus.UPLOADING) {
            throw new BizIllegalException(MediaErrorInfo.Msg.UPLOAD_TASK_NOT_UPLOADING);
        }

        // ---------- 第一层校验：数据库说齐了吗 ----------
        List<Integer> uploaded = listPartNumbers(taskId);
        List<Integer> missing = IntStream.rangeClosed(1, task.getChunkTotal())
                .filter(n -> !uploaded.contains(n))
                .boxed()
                .collect(Collectors.toList());
        if (!missing.isEmpty()) {
            throw new BizIllegalException(String.format("还缺 %d 个分片，例如第 %s 片，请补传后再合并",
                    missing.size(), missing.subList(0, Math.min(5, missing.size()))));
        }

        // ---------- 第二层校验：存储里真的齐了吗（真相源） ----------
        Map<String, Long> realObjects = fileStorage.listObjects(chunkPrefix(taskId));
        List<String> sources = new ArrayList<>(task.getChunkTotal());
        long total = 0;
        for (int n = 1; n <= task.getChunkTotal(); n++) {
            String key = chunkKey(taskId, n);
            Long realSize = realObjects.get(key);
            if (realSize == null) {
                throw new BizIllegalException("第 " + n + " 片在对象存储中不存在，请重新上传该片");
            }
            long expected = expectedPartSize(task, n);
            if (realSize != expected) {
                throw new BizIllegalException(String.format(
                        "第 %d 片大小异常：对象存储 %d 字节，期望 %d 字节", n, realSize, expected));
            }
            sources.add(key);
            total += realSize;
        }
        if (total != task.getSize()) {
            throw new BizIllegalException("分片总大小 " + total + " 与声明的 " + task.getSize() + " 不一致");
        }
        if (realObjects.size() > sources.size()) {
            log.warn("[分片] taskId={} 前缀下还有 {} 个多余对象，合并后一并清理", taskId,
                    realObjects.size() - sources.size());
        }

        // ---------- 合并 + 清理临时分片 ----------
        String contentType = ContentTypeUtil.ofName(task.getFilename());
        fileStorage.composeObject(sources, task.getObjectKey(), contentType);
        fileStorage.deleteFiles(sources);

        // ---------- 落库：物理对象 → 逻辑记录 ----------
        BizType bizType = BizType.of(task.getBizType());
        FileObject object = upsertObject(task, userId);
        File file = createFileRecord(object, task.getFilename(), bizType, userId);
        // target=media：再落一条媒资记录，前端就能直接走播放/引用那套老逻辑
        Long mediaId = TARGET_MEDIA.equals(task.getTarget())
                ? createMediaRecord(object, task.getFilename(), userId).getId()
                : null;
        updateById(new FileUploadTask().setId(taskId).setStatus(UploadStatus.COMPLETED));
        log.info("[合并] taskId={} target={} → key={} size={} fileId={} mediaId={}",
                taskId, task.getTarget(), task.getObjectKey(), total, file.getId(), mediaId);
        FileDTO dto = toFileDTO(file);
        dto.setMediaId(mediaId);
        return dto;
    }

    // ==================== ④ 取消 ====================

    @Override
    @Transactional
    public void abort(Long taskId, Long userId) {
        FileUploadTask task = requireOwnedTask(taskId, userId);
        if (task.getStatus() == UploadStatus.COMPLETED) {
            // ★ 已完成的任务不能"取消"：那已经不是上传，而是删除文件，是另一个语义
            throw new BizIllegalException("任务已完成，如需删除请调用 DELETE /files/{id}");
        }
        if (task.getStatus() == UploadStatus.CANCELLED) {
            return;
        }
        Map<String, Long> chunks = fileStorage.listObjects(chunkPrefix(taskId));
        if (!chunks.isEmpty()) {
            fileStorage.deleteFiles(new ArrayList<>(chunks.keySet()));
        }
        updateById(new FileUploadTask().setId(taskId).setStatus(UploadStatus.CANCELLED));
        log.info("[取消] taskId={} 已清理 {} 个分片对象", taskId, chunks.size());
    }

    // ==================== 内部方法 ====================

    private FileUploadTask requireOwnedTask(Long taskId, Long userId) {
        FileUploadTask task = getById(taskId);
        if (task == null) {
            throw new BizIllegalException(MediaErrorInfo.Msg.UPLOAD_TASK_NOT_EXISTS);
        }
        // ★ 归属校验：taskId 是自增的，不校验的话换个 id 就能往别人的任务里塞分片、替别人合并
        if (userId == null || !userId.equals(task.getCreater())) {
            throw new BizIllegalException(MediaErrorInfo.Msg.UPLOAD_TASK_FORBIDDEN);
        }
        return task;
    }

    /**
     * 秒传是否允许"跨用户命中"。
     *
     * <p>自己传过的永远可以；别人传的，只有<b>该前缀本来就是公开可读</b>时才允许 ——
     * 否则"知道 hash"就等价于"拿到文件"，这是一条越权入口。</p>
     */
    private boolean canInstantUpload(FileObject object, BizType bizType, Long userId) {
        if (userId != null && userId.equals(object.getCreater())) {
            return true;
        }
        return bizType.isPublicRead();
    }

    private FileObject findObject(BizType bizType, String hash) {
        return fileObjectMapper.selectOne(new LambdaQueryWrapper<FileObject>()
                .eq(FileObject::getBizType, bizType.getValue())
                .eq(FileObject::getFileHash, hash));
    }

    /**
     * 写入（或复用）物理对象，保证引用计数与 file 记录一一对应。
     *
     * <p>★ 并发 complete 同一文件时，两个请求会同时发现"没有这个对象"，
     * 然后同时插入 —— 唯一索引让第二个失败，捕获后改成 ref_count+1。
     * 这就是"锁解决效率，唯一索引解决正确性"。</p>
     */
    private FileObject upsertObject(FileUploadTask task, Long userId) {
        BizType bizType = BizType.of(task.getBizType());
        FileObject existing = findObject(bizType, task.getFileHash());
        if (existing != null) {
            fileObjectMapper.increaseRef(existing.getId());
            return existing;
        }
        FileObject object = new FileObject()
                .setFileHash(task.getFileHash())
                .setBizType(bizType.getValue())
                .setObjectKey(task.getObjectKey())
                .setSize(task.getSize())
                .setRefCount(1)
                .setCreater(userId);
        try {
            fileObjectMapper.insert(object);
            return object;
        } catch (DuplicateKeyException e) {
            FileObject concurrent = findObject(bizType, task.getFileHash());
            if (concurrent == null) {
                throw e;
            }
            log.info("[并发] 同一文件已被另一请求登记，退化为引用 +1：objectId={}", concurrent.getId());
            fileObjectMapper.increaseRef(concurrent.getId());
            return concurrent;
        }
    }

    private File createFileRecord(FileObject object, String filename, BizType bizType, Long userId) {
        File po = new File()
                .setKey(object.getObjectKey())
                .setFilename(filename)
                .setBizType(bizType)
                .setSize(object.getSize())
                .setStatus(FileStatus.UPLOADED)
                .setUrl(fileStorage.getRawUrl(object.getObjectKey()))
                .setFileHash(object.getFileHash())
                .setObjectId(object.getId())
                .setCreater(userId)
                .setDeleted(0);
        fileMapper.insert(po);
        return po;
    }

    /**
     * 产出一条媒资记录（{@code target=media} 时）。
     *
     * <p>★ 为什么"落 file 记录"还不够：{@code media} 表才是播放（预签名播放地址）
     * 与课程/考核引用的对象；只落 file，前端拿到的 id 在媒资体系里根本不存在。</p>
     *
     * <p>★ 秒传命中时这里是**新建一条 media**，不是复用已有的那条 ——
     * 两条记录共享同一个 {@code object_id}，删一条不影响另一条（引用计数在 file_object 上）。
     * 这一点依赖 {@code media.uk_file_id} 已被移除（见 sql/fm_media_m12_1.sql ⑤）。</p>
     */
    private Media createMediaRecord(FileObject object, String filename, Long userId) {
        Media media = new Media()
                .setFileId(object.getObjectKey())
                .setObjectId(object.getId())
                .setFilename(filename)
                .setMediaUrl(fileStorage.getRawUrl(object.getObjectKey()))
                .setSize(object.getSize())
                .setStatus(FileStatus.UPLOADED)
                .setUseTimes(0)
                .setCreater(userId)
                .setDeleted(0);
        mediaMapper.insert(media);
        return media;
    }

    /** 产出类型校验：只有 file / media 两种，空值按 file */
    private static String normalizeTarget(String target) {
        if (target == null || target.isBlank()) {
            return TARGET_FILE;
        }
        String t = target.trim().toLowerCase(Locale.ROOT);
        if (!TARGET_FILE.equals(t) && !TARGET_MEDIA.equals(t)) {
            throw new BizIllegalException("target 只能是 file 或 media");
        }
        return t;
    }

    private InitUploadVO buildProgress(FileUploadTask task) {
        List<Integer> parts = listPartNumbers(task.getId());
        long bytes = task.getUploadedBytes() == null ? 0L : task.getUploadedBytes();
        return new InitUploadVO()
                .setInstant(false)
                .setTaskId(task.getId())
                .setObjectKey(task.getObjectKey())
                .setChunkSize(task.getChunkSize())
                .setChunkTotal(task.getChunkTotal())
                .setUploadedChunks(parts)
                .setUploadedBytes(bytes)
                .setUploadedPercent(percent(bytes, task.getSize()));
    }

    private List<Integer> listPartNumbers(Long taskId) {
        return partMapper.selectList(new LambdaQueryWrapper<FileUploadPart>()
                        .eq(FileUploadPart::getTaskId, taskId)
                        .orderByAsc(FileUploadPart::getPartNumber))
                .stream()
                .map(FileUploadPart::getPartNumber)
                .collect(Collectors.toList());
    }

    private static int percent(long done, long total) {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.min(100, done * 100 / total);
    }

    /** 第 n 片应该多大：最后一片是"剩下的"，不一定等于 chunkSize */
    private static long expectedPartSize(FileUploadTask task, int partNumber) {
        if (partNumber < task.getChunkTotal()) {
            return task.getChunkSize();
        }
        return task.getSize() - (long) task.getChunkSize() * (task.getChunkTotal() - 1);
    }

    private String chunkPrefix(Long taskId) {
        return CHUNK_PREFIX + taskId + "/";
    }

    /** 分片 key 带 .part 后缀：便于运维按扩展名识别"这是临时分片，不是业务文件" */
    private String chunkKey(Long taskId, int partNumber) {
        return chunkPrefix(taskId) + partNumber + ".part";
    }

    private static int normalizeChunkSize(Integer requested, long size) {
        long chunkSize = (requested == null || requested <= 0) ? MIN_CHUNK_SIZE : requested;
        if (chunkSize < MIN_CHUNK_SIZE) {
            // ★ 夹紧而不是报错：前端按 1MiB 切片很常见，报错会让人以为接口坏了，
            //   而真正的原因（S3 的最小分片限制）根本不在调用方的视野里。
            log.debug("分片大小 {} 小于 S3 下限，已夹到 {}", chunkSize, MIN_CHUNK_SIZE);
            chunkSize = MIN_CHUNK_SIZE;
        }
        long minByCount = (size + MAX_CHUNK_TOTAL - 1) / MAX_CHUNK_TOTAL;
        if (chunkSize < minByCount) {
            chunkSize = minByCount;
        }
        if (chunkSize > Integer.MAX_VALUE) {
            throw new BizIllegalException("文件过大，无法计算分片大小");
        }
        return (int) chunkSize;
    }

    /**
     * 目标对象 key：{bizType}/{yyyy/MM}/{hash}.{ext}
     *
     * <p>★ 从 uuid 改成 hash 派生，是秒传能成立的前提：
     * 同一个文件无论传几次都落同一个 key，不会产生第二份字节。
     * 代价是"key 可被猜出"—— 但 key 本身不是访问凭证，bucket 是私有的，
     * 真正能打开要签名（公开前缀本来就允许匿名读）。</p>
     */
    private String buildObjectKey(BizType bizType, String hash, String filename) {
        String suffix = "";
        int idx = filename == null ? -1 : filename.lastIndexOf('.');
        if (idx > 0) {
            suffix = filename.substring(idx).toLowerCase(Locale.ROOT);
        }
        return bizType.getPath() + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"))
                + "/" + hash + suffix;
    }

    private FileDTO toFileDTO(File po) {
        FileDTO dto = new FileDTO();
        dto.setId(po.getId());
        dto.setFilename(po.getFilename());
        dto.setSize(po.getSize());
        dto.setRawUrl(po.getUrl());
        dto.setUrl(fileStorage.getAccessUrl(po.getKey(), storageProperties.getPlayExpire()));
        return dto;
    }
}
