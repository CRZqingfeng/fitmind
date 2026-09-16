package com.fitmind.promotion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 促销服务启动类。
 *
 * <p>本阶段（M4-3）有三个值得单独说的设计，都在各自的包里有详细说明：</p>
 * <ol>
 *   <li><b>20 亿量级兑换码算法</b>（{@code utils/CodeUtil}）<br>
 *       不为一个码建一条记录 —— 序列号经"新鲜值 + 校验码 + 异或混淆"编成 10 位 Base32 串，
 *       校验靠算法本身，不查库就能识别伪造码</li>
 *   <li><b>Lua 秒杀领券</b>（{@code resources/lua/receive_coupon.lua}）<br>
 *       库存、有效期、每人限领三步判断在 Redis 里一次原子执行 ——
 *       这是"库存不超卖"的关键，分开做必然有竞态</li>
 *   <li><b>叠加推荐</b>（{@code service/impl/DiscountServiceImpl}）<br>
 *       把"哪些券能一起用、怎么组合最划算"变成一个组合优化问题：
 *       排列组合出方案 → 并行算每个方案的优惠 → 双 Map 挑最优</li>
 * </ol>
 */
@SpringBootApplication(scanBasePackages = "com.fitmind")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.fitmind")
@EnableAsync
public class PromotionApplication {

    public static void main(String[] args) {
        SpringApplication.run(PromotionApplication.class, args);
    }
}
