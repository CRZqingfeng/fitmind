package com.fitmind.common.autoconfigure.xxljob;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

/**
 * XXL-JOB 执行器自动装配。
 *
 * <p>与 {@link com.fitmind.common.autoconfigure.mq.MqAutoConfiguration} 同一个套路：
 * fm-common 对 xxl-job-core 用 {@code provided} 依赖，只有真正要执行定时任务的服务
 * （fm-learning / fm-media）才引入；classpath 上没有这个类时本配置整体跳过，
 * 网关等模块零感知。</p>
 *
 * <p><b>为什么必须用 {@code @AutoConfiguration} 而不是 {@code @Configuration}</b>：
 * 这是 M1 踩过的坑——普通 {@code @Configuration} 挂 {@code @ConditionalOnClass} 不生效
 * （组件扫描阶段类已被加载，方法签名里的缺失类型直接 NoClassDefFoundError）。
 * 注册到 {@code AutoConfiguration.imports} 后，Spring Boot 会先读注解元数据再决定是否加载。</p>
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(XxlJobSpringExecutor.class)
@ConditionalOnProperty(prefix = "xxl.job.admin", name = "addresses")
@EnableConfigurationProperties(XxlJobProperties.class)
public class XxlJobConfig {

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor(XxlJobProperties prop) {
        XxlJobProperties.Executor executor = prop.getExecutor();
        log.info("初始化 XXL-JOB 执行器：admin={}, appName={}, port={}",
                prop.getAdmin().getAddresses(), executor.getAppName(), executor.getPort());

        XxlJobSpringExecutor exe = new XxlJobSpringExecutor();
        exe.setAdminAddresses(prop.getAdmin().getAddresses());
        exe.setAppname(executor.getAppName());
        exe.setPort(executor.getPort());
        exe.setAccessToken(prop.getAccessToken());
        if (StringUtils.hasText(executor.getIp())) {
            exe.setIp(executor.getIp());
        }
        if (StringUtils.hasText(executor.getLogPath())) {
            exe.setLogPath(executor.getLogPath());
        }
        if (executor.getLogRetentionDays() != null) {
            exe.setLogRetentionDays(executor.getLogRetentionDays());
        }
        return exe;
    }
}
