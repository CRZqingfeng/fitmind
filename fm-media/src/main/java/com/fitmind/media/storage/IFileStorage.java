package com.fitmind.media.storage;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 文件存储抽象（图片等通用文件）。
 *
 * <p>常见实现同时提供了阿里云、腾讯云两套实现，我们只写 MinIO 一套。
 * 但保留接口本身是有价值的：将来要换 OSS/COS，
 * 只需再加一个实现类，上层 Service 一行都不用改。</p>
 *
 * <p>★★ M12-1 起多了 4 个「分片上传」相关能力（{@link #exists}、
 * {@link #listObjects}、{@link #composeObject}、{@link #supportsMultipart}）。
 * 它们给了 <b>default 实现</b>，且默认**明确抛异常**而不是静默降级 ——</p>
 * <ul>
 *   <li>不写的实现类（目前是 OSS）不需要补代码就能编译；</li>
 *   <li>但真被调用时会立刻失败，而不是"看起来能传、其实传了半截"。</li>
 * </ul>
 * <p>这是本项目的一条硬规矩：<b>宁可 501，不要假可用</b>。</p>
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
     * 上传文件并指定 Content-Type。
     *
     * <p>★ 分片上传必须用这个重载：分片合并后的对象会保留分片对象的元数据，
     * 分片按 {@code octet-stream} 存进去，合并出来的图片/视频浏览器就**不渲染**
     * （本项目 M7-7 撞过一次，509 个对象里 51 个中招）。</p>
     *
     * <p>默认实现忽略 Content-Type，只为不关心它的后端保持兼容。</p>
     */
    default String uploadFile(String key, InputStream inputStream, long contentLength, String contentType) {
        return uploadFile(key, inputStream, contentLength);
    }

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

    // ==================== M12-1 分片上传 ====================

    /**
     * 当前后端是否支持分片上传。
     *
     * <p>Service 在 init 阶段就会问一次，不支持就直接返回业务错误 ——
     * 让前端在上传**开始前**就知道，而不是传了 40 片才失败。</p>
     */
    default boolean supportsMultipart() {
        return false;
    }

    /** 对象是否存在（秒传命中后向存储核实，避免"库里有、对象没了"） */
    default boolean exists(String key) {
        throw new UnsupportedOperationException("当前存储后端不支持对象存在性检查");
    }

    /**
     * 列出某个前缀下的所有对象。
     *
     * @return key -> 大小（字节）
     */
    default Map<String, Long> listObjects(String prefix) {
        throw new UnsupportedOperationException("当前存储后端不支持列举对象");
    }

    /**
     * 服务端合并多个对象成一个（不经过应用内存）。
     *
     * <p>★ S3 的硬约束在这里同样成立：除最后一片外，每个源对象都要 ≥ 5MiB。</p>
     */
    default void composeObject(List<String> sourceKeys, String targetKey, String contentType) {
        throw new UnsupportedOperationException("当前存储后端不支持分片合并");
    }
}

