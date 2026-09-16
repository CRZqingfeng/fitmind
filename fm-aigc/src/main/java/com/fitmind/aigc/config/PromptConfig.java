package com.fitmind.aigc.config;

import com.fitmind.aigc.prompt.NacosPromptSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提示词来源装配。
 *
 * <p>★ Nacos 是<b>可选增强</b>：关掉它（{@code fm.ai.prompt.nacos.enabled=false}）
 * 就是"纯本地文件"模式，功能完全不受影响，只是改提示词要重启服务。
 * 离线开发、Nacos 没起来、或者只是想排除"是不是配置中心的问题"时很有用。</p>
 */
@Slf4j
@Configuration
public class PromptConfig {

    @Bean
    @ConditionalOnProperty(name = "fm.ai.prompt.nacos.enabled",
            havingValue = "true", matchIfMissing = true)
    public NacosPromptSource nacosPromptSource(AigcProperties props) {
        return new NacosPromptSource(props.getPrompt().getNacos());
    }
}
