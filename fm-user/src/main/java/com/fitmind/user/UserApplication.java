package com.fitmind.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 用户服务启动类。
 *
 * <p>负责账号体系与身体档案（身高、体重、体脂率、训练目标、伤病史）。</p>
 *
 * <p>{@code scanBasePackages = "com.fitmind"} 用于扫描到 fm-common 中的
 * 全局异常处理器等公共组件。</p>
 */
@SpringBootApplication(scanBasePackages = "com.fitmind")
@EnableDiscoveryClient
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
