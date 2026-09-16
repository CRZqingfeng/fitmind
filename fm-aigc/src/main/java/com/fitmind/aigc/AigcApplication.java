package com.fitmind.aigc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * AI 健身教练启动类。
 *
 * <p><b>⚠️ 本模块是全项目唯一「不跟随父 pom 版本」的服务</b>，
 * 见 {@code pom.xml} 里的详细说明：Spring AI 1.x GA 要求 Boot 3.4+，
 * 而项目基线是 3.3.5。取舍是把升级影响面限制在这一个模块内。</p>
 *
 * <p>★ 立项四项（本项目每建一个模块都要对一遍，见排障手册 §3.18）：
 * ① openfeign ② loadbalancer ③ 本注解的 {@code @EnableFeignClients(basePackages)}
 * ④ 拦截器依赖 Feign 客户端时构造器加 {@code @Lazy}</p>
 */
@EnableFeignClients(basePackages = "com.fitmind")
@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = "com.fitmind")
public class AigcApplication {

    public static void main(String[] args) {
        SpringApplication.run(AigcApplication.class, args);
    }
}
