package com.fitmind.pay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 支付服务启动类。
 *
 * <p><b>为什么支付要独立成服务</b>：支付的变更频率和可靠性要求都和交易不同 ——
 * 渠道会换（今天是微信、明天加个支付宝）、要接第三方回调（公网可达）、
 * 有自己的对账与退款流程。把它塞进 fm-trade 会让"下单"和"接第三方"两个
 * 关注点耦合在一起。</p>
 *
 * <p><b>★ 渠道是怎么抽象的</b>：每个渠道实现 {@code IPayService} 并<b>用渠道码当 Bean 名</b>
 * （{@code @Service("mockPay")}），业务侧注入 {@code Map<String, IPayService>}
 * 按渠道码路由。这样新增渠道 = 写一个实现类 + 往 {@code pay_channel} 插一行，
 * 不用改任何业务代码。</p>
 *
 * <p>⚠️ <b>新模块立项四项</b>（每一条都是真踩过的）：
 * ① 引 openfeign（哪怕自己不写客户端）② 引 loadbalancer
 * ③ 用 Feign 客户端要加 {@code @EnableFeignClients} ④ 拦截器依赖 Feign 时构造函数加 {@code @Lazy}。</p>
 */
@SpringBootApplication(scanBasePackages = "com.fitmind")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.fitmind")
public class PayApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayApplication.class, args);
    }
}
