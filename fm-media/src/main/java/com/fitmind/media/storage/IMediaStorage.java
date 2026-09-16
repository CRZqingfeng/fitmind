package com.fitmind.media.storage;

import java.io.InputStream;
import java.util.List;

/**
 * 媒资存储抽象（视频）。
 *
 * <p>与常见实现的差异：去掉了 {@code queryMediaInfos(fileIds)}。
 * 它是腾讯云点播专用的"拉取转码结果"接口——视频传完还要等云端转码，
 * 时长和封面不是立刻能拿到的，得回头查。MinIO 上传即完成，不需要这一步。</p>
 */
public interface IMediaStorage {

    /**
     * 生成一个「上传用」的预签名 URL，供前端直传。
     *
     * @param key     对象 key
     * @param expire  有效期，单位秒
     * @param fileSize 文件大小（不参与签名，仅做记录）
     * @return 预签名 PUT 地址
     */
    String getUploadUrl(String key, int expire, long fileSize);

    /**
     * 生成一个「播放用」的预签名 URL。
     *
     * @param key    对象 key
     * @param expire 有效期，单位秒
     * @return 预签名 GET 地址
     */
    String getPlayUrl(String key, int expire);

    /**
     * 服务端中转上传（管理端小文件、测试用）。
     *
     * @return 上传结果
     */
    MediaUploadResult uploadFile(String filename, String key, InputStream inputStream, long contentLength);

    /**
     * 查询对象在存储中的真实信息（确认上传时用）。
     *
     * @param key 对象 key
     * @return 对象大小（字节）；对象不存在返回 null
     */
    Long statFile(String key);

    /** 删除单个视频 */
    void deleteFile(String key);

    /** 批量删除视频 */
    void deleteFiles(List<String> keys);
}
