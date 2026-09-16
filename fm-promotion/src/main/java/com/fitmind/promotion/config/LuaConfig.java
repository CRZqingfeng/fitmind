package com.fitmind.promotion.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * Lua 脚本配置。
 */
@Configuration
public class LuaConfig {

    /**
     * 秒杀领券脚本。
     *
     * <p>脚本在应用启动时加载一次并缓存（Redis 端也会缓存 SHA），
     * 之后每次调用只传脚本的 SHA，不重复传全文。</p>
     */
    @Bean
    public DefaultRedisScript<Long> receiveCouponScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/receive_coupon.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
