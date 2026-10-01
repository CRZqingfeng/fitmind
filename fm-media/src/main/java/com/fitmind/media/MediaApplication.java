package com.fitmind.media;

import com.fitmind.media.storage.StorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 媒资服务启动类。
 *
 * <p>负责训练视频（media）与图片文件（file）的上传、存储与播放，
 * 底层对象存储**可插拔**（MinIO / 阿里云 OSS，由 {@code fm.storage.type} 决定），
 * 对上层暴露的是 {@code IFileStorage} / {@code IMediaStorage} 抽象接口。</p>
 *
 * <p>{@code scanBasePackages = "com.fitmind"} 用于扫描到 fm-common 中的
 * 全局异常处理器等公共组件。</p>
 *
 * <p>★ {@link StorageProperties} 是**与厂商无关**的那部分配置（type / bucket / 有效期），
 * 所以在这里无条件注册它；而 MinIO 与 OSS 各自的配置类由
 * {@code @ConditionalOnProperty} 二选一装配。</p>
 */
@SpringBootApplication(scanBasePackages = "com.fitmind")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.fitmind")
@EnableConfigurationProperties(StorageProperties.class)
public class MediaApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediaApplication.class, args);
    }
}
