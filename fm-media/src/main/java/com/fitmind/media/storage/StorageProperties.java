package com.fitmind.media.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对象存储的**通用**配置（与具体云厂商无关）。
 *
 * <p><b>为什么要把这几项从 {@link MinioProperties} 里搬出来？</b></p>
 *
 * <p>因为它们是「存储」这个概念本身的属性，不是 MinIO 独有的：</p>
 * <ul>
 *   <li>{@code type} —— 二选一的后端开关（minio / oss）</li>
 *   <li>{@code bucket} —— 桶名，哪个后端都要有</li>
 *   <li>三个 {@code *-expire} —— 签名有效期，与厂商无关</li>
 * </ul>
 *
 * <p>搬出来之前，{@link com.fitmind.media.service.impl.FileServiceImpl} 与
 * {@link com.fitmind.media.service.impl.MediaServiceImpl} 为了读三个有效期而直接注入了
 * {@code MinioProperties} —— 这是「抽象漏了」的典型：一旦换成 OSS，
 * {@code MinioProperties} 这个 Bean 不再注册，**注入失败、服务起不来**。
 * 现在两个 Service 只依赖这个类，换后端与它们无关。</p>
 */
@Data
@ConfigurationProperties(prefix = "fm.storage")
public class StorageProperties {

    /**
     * 存储后端：{@code minio}（默认，本地开发）/ {@code oss}（阿里云）。
     *
     * <p>由 {@code @ConditionalOnProperty} 决定装配哪一套实现 ——
     * 改这个值 + 重启 fm-media 即可切换，<b>不用改代码、不用重新编译</b>。</p>
     */
    private String type = "minio";

    /** 桶名称 */
    private String bucket = "fitmind";

    /** 上传签名有效期，单位秒 */
    private int uploadExpire = 3600;

    /** 播放签名有效期，单位秒 */
    private int playExpire = 7200;

    /** 管理端预览签名有效期，单位秒（比播放更短） */
    private int previewExpire = 1800;
}
