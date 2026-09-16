package com.fitmind.media.storage;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 媒资（视频）存储的 MinIO 实现。
 *
 * <p>三个关键能力：</p>
 * <ol>
 *   <li><b>上传预签名</b>：前端拿到后直传 MinIO，视频不经过我们服务器</li>
 *   <li><b>播放预签名</b>：bucket 是私有的，每次播放都要换一个带签名的临时地址</li>
 *   <li><b>stat 校验</b>：确认上传时向 MinIO 核实对象是否真的存在、真实大小是多少</li>
 * </ol>
 */
@Slf4j
@Component
public class MinioMediaStorage extends AbstractMinioStorage implements IMediaStorage {

    public MinioMediaStorage(MinioClient minioClient, MinioProperties properties) {
        super(minioClient, properties);
    }

    @Override
    public String getUploadUrl(String key, int expire, long fileSize) {
        try {
            String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(properties.getBucket())
                    .object(key)
                    .expiry(expire)
                    .build());
            return toPublicUrl(url);
        } catch (Exception e) {
            log.error("生成上传预签名地址失败，key={}", key, e);
            throw new RuntimeException("生成上传地址失败", e);
        }
    }

    @Override
    public String getPlayUrl(String key, int expire) {
        try {
            String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.getBucket())
                    .object(key)
                    .expiry(expire)
                    .build());
            return toPublicUrl(url);
        } catch (Exception e) {
            log.error("生成播放预签名地址失败，key={}", key, e);
            throw new RuntimeException("生成播放地址失败", e);
        }
    }

    @Override
    public MediaUploadResult uploadFile(String filename, String key, InputStream inputStream, long contentLength) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(key)
                    .stream(inputStream, contentLength, -1)
                    .build());
            MediaUploadResult result = new MediaUploadResult();
            result.setFileId(key);
            result.setFilename(filename);
            result.setMediaUrl(buildRawUrl(key));
            return result;
        } catch (Exception e) {
            log.error("上传视频失败，key={}", key, e);
            throw new RuntimeException("上传视频失败", e);
        }
    }

    @Override
    public Long statFile(String key) {
        try {
            StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(key)
                    .build());
            return stat.size();
        } catch (ErrorResponseException e) {
            // 对象不存在（NoSuchKey）时，SDK 抛的是 ErrorResponseException，返回 null 由上层处理
            log.warn("查询对象信息失败（可能尚未上传），key={}, 错误码={}", key, e.errorResponse().code());
            return null;
        } catch (Exception e) {
            log.warn("查询对象信息异常，key={}", key, e);
            return null;
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
            log.error("删除视频失败，key={}", key, e);
            throw new RuntimeException("删除视频失败", e);
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
            for (Result<DeleteError> result : results) {
                DeleteError error = result.get();
                log.error("删除视频失败：{}", error.message());
            }
        } catch (Exception e) {
            log.error("批量删除视频失败，keys={}", keys, e);
            throw new RuntimeException("批量删除视频失败", e);
        }
    }
}
