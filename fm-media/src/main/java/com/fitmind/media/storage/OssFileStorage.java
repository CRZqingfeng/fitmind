package com.fitmind.media.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.DeleteObjectsRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * 文件存储的阿里云 OSS 实现（图片等通用文件）。
 *
 * <p>★ 仅当 {@code fm.storage.type=oss} 时装配。与 {@link MinioFileStorage} 是**同构**的：
 * 两者实现同一个 {@link IFileStorage}，上层 {@code FileServiceImpl} 一行都不用改。</p>
 *
 * <p><b>与 MinIO 实现的差异（都在基类里处理，这里只提一句）</b>：
 * URL 是 virtual-hosted、预签名必须用 publicEndpoint 建的那个客户端签、
 * 服务端 PUT 显式带上 Content-Type（顺手把 MinIO 时代"对象存成 octet-stream"的历史坑避开）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "fm.storage.type", havingValue = "oss")
public class OssFileStorage extends AbstractOssStorage implements IFileStorage {

    public OssFileStorage(@Qualifier("ossClient") OSS ossClient,
                          @Qualifier("ossPresignClient") OSS ossPresignClient,
                          OssProperties properties,
                          StorageProperties storage) {
        super(ossClient, ossPresignClient, properties, storage);
    }

    @Override
    public String uploadFile(String key, InputStream inputStream, long contentLength) {
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(contentLength);
            // ★ 服务端中转上传时也把 Content-Type 写对：
            //   不写的话 OSS 会存成 application/octet-stream，
            //   浏览器就不会把非 image/* 的响应当图片渲染（MinIO 时代踩过，见 #A7）
            metadata.setContentType(guessContentType(key));
            ossClient.putObject(bucket(), key, inputStream, metadata);
            return key;
        } catch (Exception e) {
            log.error("OSS 上传文件失败，key={}", key, e);
            throw new RuntimeException("上传文件失败", e);
        }
    }

    @Override
    public String getRawUrl(String key) {
        return buildRawUrl(key);
    }

    @Override
    public String getAccessUrl(String key, int expire) {
        try {
            return presign(key, HttpMethod.GET, expire, null);
        } catch (Exception e) {
            log.error("OSS 生成文件访问地址失败，key={}", key, e);
            throw new RuntimeException("生成文件访问地址失败", e);
        }
    }

    @Override
    public String getAccessUrlByRawUrl(String rawUrl, int expire) {
        String key = extractKey(rawUrl);
        if (key == null || key.isBlank()) {
            return null;
        }
        return getAccessUrl(key, expire);
    }

    @Override
    public InputStream downloadFile(String key) {
        try {
            OSSObject object = ossClient.getObject(bucket(), key);
            return object.getObjectContent();
        } catch (Exception e) {
            log.error("OSS 下载文件失败，key={}", key, e);
            throw new RuntimeException("下载文件失败", e);
        }
    }

    @Override
    public void deleteFile(String key) {
        try {
            ossClient.deleteObject(bucket(), key);
        } catch (Exception e) {
            log.error("OSS 删除文件失败，key={}", key, e);
            throw new RuntimeException("删除文件失败", e);
        }
    }

    @Override
    public void deleteFiles(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        try {
            // OSS 批量删除一次最多 1000 个 key，这里分片，避免"条目多了静默失败"
            for (int i = 0; i < keys.size(); i += 1000) {
                List<String> batch = keys.subList(i, Math.min(i + 1000, keys.size()));
                ossClient.deleteObjects(new DeleteObjectsRequest(bucket()).withKeys(batch));
            }
        } catch (Exception e) {
            log.error("OSS 批量删除文件失败，keys={}", keys, e);
            throw new RuntimeException("批量删除文件失败", e);
        }
    }
}
