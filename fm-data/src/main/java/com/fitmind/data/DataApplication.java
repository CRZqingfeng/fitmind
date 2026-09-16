package com.fitmind.data;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 数据中心启动类。
 *
 * <p><b>本模块的定位：只读的统计侧。</b></p>
 *
 * <p>它<b>不生产任何业务事件</b>，只订阅别人已经发出的事件（签到、完成训练、
 * 小测、支付、退款、体测），把"发生了什么"累加成"今天怎么样"。
 * 这么做的好处是<b>业务服务完全不知道数据中心的存在</b> ——
 * 数据中心挂了、慢了、统计口径改了，都不影响用户下单和学习。</p>
 *
 * <p>★ 这也印证了 M4-4 那次"通知走 MQ 而不是同步 Feign"的取舍：
 * 当时多写一个消费者看起来是过度设计，现在加数据中心时
 * <b>生产方一行代码都不用动</b>。</p>
 */
@EnableFeignClients(basePackages = "com.fitmind")
@SpringBootApplication(scanBasePackages = "com.fitmind")
public class DataApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataApplication.class, args);
    }
}
