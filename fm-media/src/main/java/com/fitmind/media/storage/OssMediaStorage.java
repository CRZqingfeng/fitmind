package com.fitmind.media.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.DeleteObjectsRequest;
import com.aliyun.oss.model.ObjectMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * 媒资（视频）存储的阿里云 OSS 实现。
 *
 * <p>★ 仅当 {@code fm.storage.type=oss} 时装配，与 {@link MinioMediaStorage} 同构。</p>
 *
 * <p><b>两个必须记住的差异</b></p>
 * <ol>
 *   <li><b>上传签名要把 Content-Type 签进去</b>：OSS V4 会校验它，而浏览器直传
 *       一定会带 Content-Type。所以这里按对象 key 的扩展名算出值去签，
 *       并把它回传给前端（{@code UploadSignatureDTO.contentType}）原样带上。
 *       ⚠️ 这条与 MinIO 不同 —— MinIO 不校验 Content-Type，签不签都行。</li>
 *   <li><b>{@code getObjectMetadata} 对不存在的对象是<b>抛异常</b>而不是返回 null</b>
 *       （{@code OSSException}，errorCode = {@code NoSuchKey}）。
 *       不捕获的话，"前端还没传完就来确认"会变成 500，而不是友好的"尚未上传"。</li>
 * </ol>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "fm.storage.type", havingValue = "oss")
public class OssMediaStorage extends AbstractOssStorage implements IMediaStorage {

    public OssMediaStorage(@Qualifier("ossClient") OSS ossClient,
                           @Qualifier("ossPresignClient") OSS ossPresignClient,
                           OssProperties properties,
                           StorageProperties storage) {
        super(ossClient, ossPresignClient, properties, storage);
    }

    @Override
    public boolean requiresContentTypeOnUpload() {
        // ★ OSS 的 V4 签名把 Content-Type 算进签名，前端必须带一模一样的值
        return true;
    }

    @Override
    public String getUploadUrl(String key, int expire, long fileSize) {
        try {
            // ★★ Content-Type 必须纳入签名（原因见类注释 ①）
            return presign(key, HttpMethod.PUT, expire, guessContentType(key));
        } catch (Exception e) {
            log.error("OSS 生成上传预签名地址失败，key={}", key, e);
            throw new RuntimeException("生成上传地址失败", e);
        }
    }

    @Override
    public String getPlayUrl(String key, int expire) {
        try {
            return presign(key, HttpMethod.GET, expire, null);
        } catch (Exception e) {
            log.error("OSS 生成播放预签名地址失败，key={}", key, e);
            throw new RuntimeException("生成播放地址失败", e);
        }
    }

    @Override
    public MediaUploadResult uploadFile(String filename, String key, InputStream inputStream, long contentLength) {
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(contentLength);
            metadata.setContentType(guessContentType(filename));
            ossClient.putObject(bucket(), key, inputStream, metadata);

            MediaUploadResult result = new MediaUploadResult();
            result.setFileId(key);
            result.setFilename(filename);
            result.setMediaUrl(buildRawUrl(key));
            return result;
        } catch (Exception e) {
            log.error("OSS 上传视频失败，key={}", key, e);
            throw new RuntimeException("上传视频失败", e);
        }
    }

    @Override
    public Long statFile(String key) {
        try {
            ObjectMetadata meta = ossClient.getObjectMetadata(bucket(), key);
            return meta.getContentLength();
        } catch (OSSException e) {
            // ★★ 对象不存在时 OSS 抛的是 OSSException(NoSuchKey)，**不是返回 null**
            //    （与 MinIO 的 ErrorResponseException 同位置、不同异常类型）
            log.warn("OSS 查询对象信息失败（可能尚未上传），key={}, 错误码={}, 消息={}",
                    key, e.getErrorCode(), e.getErrorMessage());
            return null;
        } catch (Exception e) {
            log.warn("OSS 查询对象信息异常，key={}", key, e);
            return null;
        }
    }

    @Override
    public void deleteFile(String key) {
        try {
            ossClient.deleteObject(bucket(), key);
        } catch (Exception e) {
            log.error("OSS 删除视频失败，key={}", key, e);
            throw new RuntimeException("删除视频失败", e);
        }
    }

    @Override
    public void deleteFiles(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        try {
            for (int i = 0; i < keys.size(); i += 1000) {
                List<String> batch = keys.subList(i, Math.min(i + 1000, keys.size()));
                ossClient.deleteObjects(new DeleteObjectsRequest(bucket()).withKeys(batch));
            }
        } catch (Exception e) {
            log.error("OSS 批量删除视频失败，keys={}", keys, e);
            throw new RuntimeException("批量删除视频失败", e);
        }
    }
}
