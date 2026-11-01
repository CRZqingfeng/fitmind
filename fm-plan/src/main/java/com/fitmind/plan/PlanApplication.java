package com.fitmind.plan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 计划服务启动类。
 *
 * <p>职责：训练计划模板编排（计划 → 训练日 → 动作项）、执行计划（加入我的计划）、
 * 今日训练、逐组打卡。P1 追加饮食侧。</p>
 *
 * <p><b>最容易写错的地方</b>：{@code scanBasePackages} 必须是 {@code com.fitmind} 而不是
 * {@code com.fitmind.plan}。fm-common 里的全局异常处理器（{@code CommonExceptionAdvice}）、
 * 统一返回包装（{@code WrapperResponseBodyAdvice}）都在 {@code com.fitmind.common} 下，
 * 只扫本包的话接口不会报错，但**所有异常都会变成 Spring 默认的 500 白页**，
 * 前端拿不到 {@code code/msg}，排查时完全看不出是哪一步错了。</p>
 *
 * <p>{@code @EnableFeignClients} P0 没有跨服务调用，但保留它：
 * 后续"训练完成 → 通知 fm-learning 加积分"要在这里加 Client，提前对齐其他服务。</p>
 */
@SpringBootApplication(scanBasePackages = "com.fitmind")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.fitmind")
public class PlanApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlanApplication.class, args);
    }
}
