package com.fitmind.remark;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 点赞服务启动类。
 *
 * <p>为什么点赞要独立成一个服务，而不是塞进 fm-learning：</p>
 * <ol>
 *   <li><b>能力跨业务</b>：点赞不只服务问答和笔记，后续课程评价、教练评价都用同一套；
 *       业务方只要传一个 {@code bizType} 就能接入</li>
 *   <li><b>写热点可独立伸缩</b>：点赞是"读多写多、单条内容可能瞬间爆热"的场景，
 *       独立部署后可单独扩容/限流，不会因为点赞风暴拖垮学习服务</li>
 *   <li><b>数据归属清晰</b>：点赞明细 {@code liked_record} 在本服务的库；
 *       业务表上的 {@code liked_times} 退化为<b>冗余计数</b>，通过 MQ 异步同步</li>
 * </ol>
 *
 * <p>{@code scanBasePackages = "com.fitmind"} 用于扫描 fm-common 中的全局异常处理器、
 * MQ 工具、XXL-JOB 执行器等公共组件。</p>
 */
@SpringBootApplication(scanBasePackages = "com.fitmind")
@EnableDiscoveryClient
public class RemarkApplication {

    public static void main(String[] args) {
        SpringApplication.run(RemarkApplication.class, args);
    }
}
