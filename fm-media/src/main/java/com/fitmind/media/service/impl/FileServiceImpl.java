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
import com.fitmind.media.mapper.FileObjectMapper;
import com.fitmind.media.service.IFileService;
import com.fitmind.media.storage.IFileStorage;
import com.fitmind.media.storage.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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

    /** M12-1：删除共享物理对象时要维护引用计数 */
    private final FileObjectMapper fileObjectMapper;

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
    @Transactional
    public void deleteFileById(Long id) {
        File po = getById(id);
        if (po == null) {
            return;
        }
        if (po.getObjectId() == null) {
            // 老数据（M12-1 之前上传）：key 是这条记录独占的，直接删对象
            fileStorage.deleteFile(po.getKey());
        } else {
            /**
             * ★★ 共享物理对象：不能直接删字节。
             *
             * <p>秒传产生的多条 file 记录指向同一个 key，直接删对象会让别人的文件凭空消失。
             * 正确顺序是「先减引用 → 归零才删行 → 删行成功才删字节」：</p>
             * <ul>
             *   <li>减引用带 {@code ref_count > 0} 条件，并发删除不会减成负数；</li>
             *   <li>删行带 {@code ref_count <= 0} 条件，让"能不能删"由数据库当次的值决定；</li>
             *   <li>先删行再删字节 —— 反过来会出现"引用还加得上、但字节已经没了"的鬼记录。</li>
             * </ul>
             * <p>代价：删行成功后删字节失败会留下孤儿对象。这是**有意接受**的方向 ——
             * 孤儿只浪费存储，反方向则是数据丢失。</p>
             */
            int affected = fileObjectMapper.decreaseRef(po.getObjectId());
            if (affected == 0) {
                log.warn("物理对象 {} 的引用计数已为 0，跳过递减（可能是重复删除）", po.getObjectId());
            } else if (fileObjectMapper.deleteIfUnused(po.getObjectId()) > 0) {
                deleteObjectAfterCommit(po.getKey());
                log.info("物理对象 {} 已无引用，删除对象 {}", po.getObjectId(), po.getKey());
            }
        }
        removeById(id);
    }

    /**
     * 把「删对象存储」推迟到事务提交之后。
     *
     * <p><b>为什么不直接删：</b>这是一个 {@code @Transactional} 方法，直接在里面发 HTTP，
     * 会做两件不该做的事 ——</p>
     * <ol>
     *   <li><b>占着数据库连接做网络 IO</b>。对象存储慢 3 秒，数据库连接就被占 3 秒；
     *       高并发下连接池会被"等对象存储"的请求占满，表现为**全站变慢**，
     *       而慢的根因完全不在数据库上。</li>
     *   <li><b>删了字节却回滚了数据库</b>：对象删除无法回滚，一旦后续 SQL 失败，
     *       库里留下一条指向"已经不存在的对象"的记录。</li>
     * </ol>
     * <p>放到 afterCommit 之后：事务成功才删字节，失败就当没发生过。
     * 代价是"提交成功但删字节失败"会留孤儿对象 —— 这个方向是**有意选的**，
     * 孤儿只浪费存储，反方向是数据丢失（由桶的生命周期规则兜底回收）。</p>
     */
    private void deleteObjectAfterCommit(String key) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    fileStorage.deleteFile(key);
                }
            });
        } else {
            fileStorage.deleteFile(key);
        }
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
