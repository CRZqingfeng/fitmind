package com.fitmind.media.storage;

import java.io.InputStream;
import java.util.List;

/**
 * 文件存储抽象（图片等通用文件）。
 *
 * <p>常见实现同时提供了阿里云、腾讯云两套实现，我们只写 MinIO 一套。
 * 但保留接口本身是有价值的：将来要换 OSS/COS，
 * 只需再加一个实现类，上层 Service 一行都不用改。</p>
 */
public interface IFileStorage {

    /**
     * 上传文件（服务端中转）。
     *
     * @param key           对象存储中的唯一 key，如 {@code avatar/2026/09/uuid.jpg}
     * @param inputStream   文件流
     * @param contentLength 文件大小，单位字节
     * @return 对象 key
     */
    String uploadFile(String key, InputStream inputStream, long contentLength);

    /**
     * 拼出不带签名的原始地址（记录在库里用，不能直接访问）。
     */
    String getRawUrl(String key);

    /**
     * 生成一个带签名的临时访问地址。
     *
     * <p>bucket 是私有的，不带签名的地址浏览器打不开；
     * 所以返回给前端展示（比如头像、封面图）时必须走这个方法。</p>
     *
     * @param key    对象 key
     * @param expire 有效期，单位秒
     */
    String getAccessUrl(String key, int expire);

    /**
     * 根据完整原始地址生成带签名的访问地址（内部会反解出 key）。
     *
     * @param rawUrl 不带签名的完整地址
     * @param expire 有效期，单位秒
     * @return 带签名的地址；rawUrl 为空时返回 null
     */
    String getAccessUrlByRawUrl(String rawUrl, int expire);

    /** 下载文件 */
    InputStream downloadFile(String key);

    /** 删除单个文件 */
    void deleteFile(String key);

    /** 批量删除文件 */
    void deleteFiles(List<String> keys);
}
