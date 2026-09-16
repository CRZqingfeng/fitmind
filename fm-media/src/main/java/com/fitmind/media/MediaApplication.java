package com.fitmind.media;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 媒资服务启动类。
 *
 * <p>负责训练视频（media）与图片文件（file）的上传、存储与播放，
 * 底层对象存储为 MinIO，对上层暴露的是 {@code IFileStorage} / {@code IMediaStorage} 抽象接口。</p>
 *
 * <p>{@code scanBasePackages = "com.fitmind"} 用于扫描到 fm-common 中的
 * 全局异常处理器等公共组件。</p>
 */
@SpringBootApplication(scanBasePackages = "com.fitmind")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.fitmind")
public class MediaApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediaApplication.class, args);
    }
}
