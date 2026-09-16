package com.fitmind.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 搜索服务启动类。
 *
 * <p>职责边界很清楚：<b>这里不产生任何数据</b>，只做「把别处的数据翻译成 ES 文档」:</p>
 * <ol>
 *   <li>消费 fm-course 的课程上下架消息 → 同步 / 删除 ES 索引</li>
 *   <li>对外提供关键词检索、榜单推荐、兴趣管理</li>
 * </ol>
 *
 * <p>{@code scanBasePackages = "com.fitmind"} 用于扫描到 fm-common 中的
 * 全局异常处理器、MQ 工具等公共组件。</p>
 */
@SpringBootApplication(scanBasePackages = "com.fitmind")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.fitmind")
public class SearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchApplication.class, args);
    }
}
