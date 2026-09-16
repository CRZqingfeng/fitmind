package com.fitmind.media.storage;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文件存储的 MinIO 实现（图片等通用文件）。
 */
@Slf4j
@Component
public class MinioFileStorage extends AbstractMinioStorage implements IFileStorage {

    public MinioFileStorage(MinioClient minioClient, MinioProperties properties) {
        super(minioClient, properties);
    }

    @Override
    public String uploadFile(String key, InputStream inputStream, long contentLength) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(key)
                    .stream(inputStream, contentLength, -1)
                    .build());
            return key;
        } catch (Exception e) {
            log.error("上传文件失败，key={}", key, e);
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
            String url = minioClient.getPresignedObjectUrl(io.minio.GetPresignedObjectUrlArgs.builder()
                    .method(io.minio.http.Method.GET)
                    .bucket(properties.getBucket())
                    .object(key)
                    .expiry(expire)
                    .build());
            return toPublicUrl(url);
        } catch (Exception e) {
            log.error("生成文件访问地址失败，key={}", key, e);
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
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(key)
                    .build());
        } catch (Exception e) {
            log.error("下载文件失败，key={}", key, e);
            throw new RuntimeException("下载文件失败", e);
        }
    }

    @Override
    public void deleteFile(String key) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(key)
                    .build());
        } catch (Exception e) {
            log.error("删除文件失败，key={}", key, e);
            throw new RuntimeException("删除文件失败", e);
        }
    }

    @Override
    public void deleteFiles(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        try {
            List<DeleteObject> objects = keys.stream()
                    .map(DeleteObject::new)
                    .collect(Collectors.toList());
            Iterable<Result<DeleteError>> results = minioClient.removeObjects(RemoveObjectsArgs.builder()
                    .bucket(properties.getBucket())
                    .objects(objects)
                    .build());
            // 必须遍历一次才会真正触发删除请求；顺便收集失败项
            for (Result<DeleteError> result : results) {
                DeleteError error = result.get();
                log.error("删除对象失败：{}", error.message());
            }
        } catch (Exception e) {
            log.error("批量删除文件失败，keys={}", keys, e);
            throw new RuntimeException("批量删除文件失败", e);
        }
    }
}
