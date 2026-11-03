package com.fitmind.media.storage;

import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文件存储的 MinIO 实现（图片等通用文件）。
 *
 * <p>★ 仅当 {@code fm.storage.type=minio}（或未配置）时装配 —— 切到 OSS 后本类不注册，
 * 由 {@code OssFileStorage} 顶上。两者实现同一个 {@link IFileStorage}，上层无感。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "fm.storage.type", havingValue = "minio", matchIfMissing = true)
public class MinioFileStorage extends AbstractMinioStorage implements IFileStorage {

    public MinioFileStorage(MinioClient minioClient, MinioProperties properties, StorageProperties storage) {
        super(minioClient, properties, storage);
    }

    @Override
    public String uploadFile(String key, InputStream inputStream, long contentLength) {
        return uploadFile(key, inputStream, contentLength, null);
    }

    @Override
    public String uploadFile(String key, InputStream inputStream, long contentLength, String contentType) {
        try {
            PutObjectArgs.Builder builder = PutObjectArgs.builder()
                    .bucket(storage.getBucket())
                    .object(key)
                    .stream(inputStream, contentLength, -1);
            // ★ 分片对象必须带 Content-Type：合并后的对象沿用分片对象的元数据，
            //   不带就是 binary/octet-stream → <img>/<video> 不渲染，直链却返回 200。
            if (contentType != null && !contentType.isBlank()) {
                builder.contentType(contentType);
            }
            minioClient.putObject(builder.build());
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
                    .bucket(storage.getBucket())
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
                    .bucket(storage.getBucket())
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
                    .bucket(storage.getBucket())
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
                    .bucket(storage.getBucket())
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

    // ==================== M12-1 分片上传 ====================

    @Override
    public boolean supportsMultipart() {
        return true;
    }

    /**
     * ★ 这里没有用 S3 的 Multipart Upload API，而是"分片各存一个对象 + compose 合并"。
     *
     * <p>原因（实读过 SDK，不是猜的）：minio-java <b>8.5.12</b> 把
     * {@code createMultipartUpload / uploadPart / completeMultipartUpload}
     * 全部声明为 {@code S3Base} 的 <b>protected</b> 方法，外部拿不到；
     * 公开 API 只有"整对象 putObject（内部自动分片，不可中断续传）"。
     * 要用原生 multipart 就得自己算 SigV4 直接发 HTTP，那是另一个量级的复杂度。</p>
     *
     * <p>compose 合并走的是服务端 UploadPartCopy，**不占应用内存与带宽**，
     * 分片对象还天然成为"哪些片传过了"的真相源（列前缀即可）。代价是多一层临时对象，
     * 合并后必须删掉（见 Service 的 complete/abort）。</p>
     */
    @Override
    public boolean exists(String key) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(storage.getBucket())
                    .object(key)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            // ★ 只有明确的"对象不存在"才返回 false；
            //   403/5xx 必须往上抛 —— 把"没权限"当成"不存在"会导致秒传永远不命中，
            //   而且错误被吞掉，排查时完全看不到。
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            log.error("检查对象是否存在失败，key={}", key, e);
            throw new RuntimeException("检查对象是否存在失败", e);
        } catch (Exception e) {
            log.error("检查对象是否存在失败，key={}", key, e);
            throw new RuntimeException("检查对象是否存在失败", e);
        }
    }

    @Override
    public Map<String, Long> listObjects(String prefix) {
        Map<String, Long> result = new LinkedHashMap<>();
        try {
            Iterable<Result<Item>> items = minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(storage.getBucket())
                    .prefix(prefix)
                    // ★ recursive=true：分片 key 里没有"/"以外的层级，但显式写清楚更安全
                    .recursive(true)
                    .build());
            for (Result<Item> item : items) {
                Item i = item.get();
                result.put(i.objectName(), i.size());
            }
            return result;
        } catch (Exception e) {
            log.error("列举对象失败，prefix={}", prefix, e);
            throw new RuntimeException("列举对象失败", e);
        }
    }

    @Override
    public void composeObject(List<String> sourceKeys, String targetKey, String contentType) {
        if (sourceKeys == null || sourceKeys.isEmpty()) {
            throw new IllegalArgumentException("合并失败：没有任何分片对象");
        }
        try {
            if (sourceKeys.size() == 1) {
                // ★ 只有一片时不能 compose（S3 的 CompleteMultipartUpload 至少要 1 片，
                //   但 MinIO 的 compose 对单源的行为没保证），直接用服务端复制
                minioClient.copyObject(CopyObjectArgs.builder()
                        .bucket(storage.getBucket())
                        .object(targetKey)
                        .source(CopySource.builder()
                                .bucket(storage.getBucket())
                                .object(sourceKeys.get(0))
                                .build())
                        .build());
                return;
            }
            List<ComposeSource> sources = new ArrayList<>(sourceKeys.size());
            for (String key : sourceKeys) {
                sources.add(ComposeSource.builder()
                        .bucket(storage.getBucket())
                        .object(key)
                        .build());
            }
            ComposeObjectArgs.Builder builder = ComposeObjectArgs.builder()
                    .bucket(storage.getBucket())
                    .object(targetKey)
                    .sources(sources);
            if (contentType != null && !contentType.isBlank()) {
                builder.headers(Map.of("Content-Type", contentType));
            }
            minioClient.composeObject(builder.build());
        } catch (Exception e) {
            log.error("合并分片失败，target={}, sources={}", targetKey, sourceKeys.size(), e);
            throw new RuntimeException("合并分片失败: " + e.getMessage(), e);
        }
    }
}
