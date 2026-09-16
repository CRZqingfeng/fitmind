package com.fitmind.exam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 体测评估服务启动类。
 *
 * <p><b>★ 本模块没有参考实现可用</b>（常见实现的测评服务 是通用题库，
 * 与健身的"体测评估"无关），所以是纯原创设计。核心在两处：</p>
 * <ol>
 *   <li>{@code util/BodyMetricsUtil} —— 五项指标的公式与分级。
 *       里面有几个容易抄错的领域知识：BMI 用<b>中国标准</b>（超重 24 而非 25）、
 *       体脂率与腰臀比的<b>正常范围男女不同</b>、BMR 用 Mifflin-St Jeor 公式。</li>
 *   <li>{@code service/impl/BodyAssessmentServiceImpl} —— 把指标翻译成
 *       "所以你该怎么做"（热量目标 + 训练侧重 + 风险提示）。
 *       只有数字没有建议，用户拿到 BMI 22.3 也还是不知道该练什么。</li>
 * </ol>
 *
 * <p>另外一条贯穿本模块的硬规则：<b>所有算出来的指标都不接受前端传值</b>，
 * 改个请求体就能把自己的 BMI 说成正常，那评估就没有意义了。</p>
 */
@SpringBootApplication(scanBasePackages = "com.fitmind")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.fitmind")
public class ExamApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExamApplication.class, args);
    }
}
