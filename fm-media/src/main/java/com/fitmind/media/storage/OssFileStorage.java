package com.fitmind.media.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.AbortMultipartUploadRequest;
import com.aliyun.oss.model.CompleteMultipartUploadRequest;
import com.aliyun.oss.model.DeleteObjectsRequest;
import com.aliyun.oss.model.InitiateMultipartUploadRequest;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PartETag;
import com.aliyun.oss.model.UploadPartCopyRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        return uploadFile(key, inputStream, contentLength, null);
    }

    /**
     * ★ 分片上传走这个重载：分片 key 是 {@code chunks/{taskId}/1.part}，
     * 按 key 猜出来的 MIME 一定是 octet-stream —— 合并后的视频就播不了。
     */
    @Override
    public String uploadFile(String key, InputStream inputStream, long contentLength, String contentType) {
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(contentLength);
            // ★ 服务端中转上传时也把 Content-Type 写对：
            //   不写的话 OSS 会存成 application/octet-stream，
            //   浏览器就不会把非 image/* 的响应当图片渲染（MinIO 时代踩过，见 #A7）
            metadata.setContentType(isBlank(contentType) ? guessContentType(key) : contentType);
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

    // ==================== M12-1 分片上传（OSS 侧） ====================

    @Override
    public boolean supportsMultipart() {
        return true;
    }

    @Override
    public boolean exists(String key) {
        try {
            return ossClient.doesObjectExist(bucket(), key);
        } catch (Exception e) {
            // ★ 只有明确的"不存在"才返回 false；鉴权/网络问题必须往上抛 ——
            //   把异常吞成 false 会让秒传永远不命中，而且完全看不出原因。
            log.error("OSS 检查对象是否存在失败，key={}", key, e);
            throw new RuntimeException("检查对象是否存在失败", e);
        }
    }

    /**
     * 列出前缀下的对象（分页）。
     *
     * <p>★ 必须翻页：OSS 单次最多返回 1000 条，而分片最多 1 万片 ——
     * 只取第一页会让"第 1001 片之后算没传"，合并时永远缺片。</p>
     */
    @Override
    public Map<String, Long> listObjects(String prefix) {
        Map<String, Long> result = new LinkedHashMap<>();
        try {
            String marker = null;
            boolean truncated;
            do {
                ListObjectsRequest request = new ListObjectsRequest(bucket())
                        .withPrefix(prefix)
                        .withMaxKeys(1000);
                if (marker != null) {
                    request.setMarker(marker);
                }
                ObjectListing listing = ossClient.listObjects(request);
                for (OSSObjectSummary summary : listing.getObjectSummaries()) {
                    result.put(summary.getKey(), summary.getSize());
                }
                marker = listing.getNextMarker();
                truncated = listing.isTruncated();
            } while (truncated);
            return result;
        } catch (Exception e) {
            log.error("OSS 列举对象失败，prefix={}", prefix, e);
            throw new RuntimeException("列举对象失败", e);
        }
    }

    /**
     * 服务端合并多个分片对象成正式对象。
     *
     * <p><b>OSS 没有 MinIO 那种 {@code composeObject} 一步合并</b>，
     * 等价做法是「初始化一个分片上传 → 逐片 {@code UploadPartCopy} 把分片拷进去 →
     * Complete」，全程服务端拷贝，不占应用带宽与内存。</p>
     *
     * <p>★ 失败必须 {@code abort}：否则 OSS 上会留下一个看不见的未完成分片上传，
     * 既按存储计费，又永远不会被"删对象"删掉（它还没成为对象）。</p>
     */
    @Override
    public void composeObject(List<String> sourceKeys, String targetKey, String contentType) {
        if (sourceKeys == null || sourceKeys.isEmpty()) {
            throw new IllegalArgumentException("合并失败：没有任何分片对象");
        }
        try {
            // 单片：直接服务端拷贝（OSS 对单源 multipart 的行为没保证，不走合并）
            if (sourceKeys.size() == 1) {
                ossClient.copyObject(bucket(), sourceKeys.get(0), bucket(), targetKey);
                return;
            }
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(isBlank(contentType) ? guessContentType(targetKey) : contentType);
            InitiateMultipartUploadRequest initRequest =
                    new InitiateMultipartUploadRequest(bucket(), targetKey);
            initRequest.setObjectMetadata(metadata);
            String uploadId = ossClient.initiateMultipartUpload(initRequest).getUploadId();
            try {
                List<PartETag> etags = new ArrayList<>(sourceKeys.size());
                for (int i = 0; i < sourceKeys.size(); i++) {
                    // ★ 分片号必须从 1 开始且连续 —— OSS 的 CompleteMultipartUpload 只认这个
                    UploadPartCopyRequest copyRequest = new UploadPartCopyRequest(
                            bucket(), sourceKeys.get(i), bucket(), targetKey, uploadId);
                    copyRequest.setPartNumber(i + 1);
                    etags.add(ossClient.uploadPartCopy(copyRequest).getPartETag());
                }
                ossClient.completeMultipartUpload(
                        new CompleteMultipartUploadRequest(bucket(), targetKey, uploadId, etags));
            } catch (Exception e) {
                try {
                    ossClient.abortMultipartUpload(
                            new AbortMultipartUploadRequest(bucket(), targetKey, uploadId));
                } catch (Exception abortError) {
                    log.warn("合并失败后 abort 也失败，uploadId={}", uploadId, abortError);
                }
                throw e;
            }
        } catch (Exception e) {
            log.error("OSS 合并分片失败，target={}, sources={}", targetKey, sourceKeys.size(), e);
            throw new RuntimeException("合并分片失败: " + e.getMessage(), e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
