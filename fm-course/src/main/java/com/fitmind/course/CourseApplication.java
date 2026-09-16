package com.fitmind.course;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 课程服务启动类。
 *
 * <p>负责训练课程、三级分类、训练目录的管理。核心是<b>「草稿-发布」机制</b>：
 * 教练编辑改草稿表，点上架才整体同步到正式表，保证编辑过程不影响线上学员。</p>
 *
 * <p>{@code scanBasePackages = "com.fitmind"} 用于扫描到 fm-common 中的
 * 全局异常处理器等公共组件。</p>
 */
@SpringBootApplication(scanBasePackages = "com.fitmind")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.fitmind")
public class CourseApplication {

    public static void main(String[] args) {
        SpringApplication.run(CourseApplication.class, args);
    }
}
