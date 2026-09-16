package com.fitmind.learning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 学习服务启动类。
 *
 * <p>职责：报名（课表）、学习进度（动作级）、断点续播、学习计划、过期任务。</p>
 *
 * <p>两个有技术含量的点：</p>
 * <ol>
 *   <li><b>分布式进度合并</b>：播放中每 15 秒一次的上报，经 Redis ZSet 延迟队列合并成
 *       停播后的一次落库（多实例安全，替代常见实现的单机 DelayQueue）</li>
 *   <li><b>XXL-JOB 执行器</b>：课表过期扫描走分布式调度而不是 @Scheduled</li>
 * </ol>
 *
 * <p>{@code scanBasePackages = "com.fitmind"} 用于扫描到 fm-common 中的
 * 全局异常处理器、MQ 工具、XXL-JOB 执行器等公共组件。</p>
 */
@SpringBootApplication(scanBasePackages = "com.fitmind")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.fitmind")
public class LearningApplication {

    public static void main(String[] args) {
        SpringApplication.run(LearningApplication.class, args);
    }
}
