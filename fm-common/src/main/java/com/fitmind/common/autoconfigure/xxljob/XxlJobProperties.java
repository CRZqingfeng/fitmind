package com.fitmind.common.autoconfigure.xxljob;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * XXL-JOB 执行器配置项。
 *
 * <p>配置示例（业务服务的 application.yml）：</p>
 * <pre>
 * xxl:
 *   job:
 *     admin:
 *       addresses: http://127.0.0.1:9090/xxl-job-admin
 *     accessToken: default_token
 *     executor:
 *       appname: fm-learning
 *       port: 9995
 *       logPath: ./logs/xxl-job/fm-learning
 *       logRetentionDays: 7
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "xxl.job")
public class XxlJobProperties {

    /** 调度中心通讯令牌，须与 admin 一致 */
    private String accessToken;

    private Admin admin = new Admin();

    private Executor executor = new Executor();

    @Data
    public static class Admin {
        /** 调度中心地址；为空则不启动执行器（允许开发时只引依赖不连调度中心） */
        private String addresses;
    }

    @Data
    public static class Executor {
        /** 执行器AppName，与 admin 控制台里配置的一致 */
        private String appName;
        /** 执行器通讯端口，每个服务必须不同 */
        private Integer port = 9999;
        private String address;
        private String ip;
        private String logPath = "./logs/xxl-job";
        private Integer logRetentionDays = 7;
    }
}
