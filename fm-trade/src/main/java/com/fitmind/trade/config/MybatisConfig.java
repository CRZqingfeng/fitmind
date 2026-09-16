package com.fitmind.trade.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：<b>分页插件必须显式注册</b>。
 *
 * <p>★ 这个类是 M6-1 补上的 —— 本模块此前<b>用了分页但没有注册插件</b>，
 * 于是 selectPage() 不写 SQL 分页，表现是：
 * <b>查全表、total 恒为 0</b>（接口 200、list 里有数据，看着完全正常）。
 * 数据量小的时候发现不了，订单量一大就是"订单列表突然很慢"的事故。</p>
 *
 * <p>发现它的过程：M6-1 给 fm-aigc 写完会话列表后看到 total=0，
 * 顺手扫了一遍所有模块，才发现有 3 个模块（含本模块）漏了这个配置 ——
 * 属于<b>已存在但一直没被发现的缺陷</b>。</p>
 *
 * <p>⚠️ 现状是"每个用到分页的模块各配一份"（course / learning / media /
 * aigc / 本模块）。五份相同代码说明它<b>该被提取到 fm-common 的自动装配</b>，
 * 但那会一次性影响 14 个服务、且需要先删掉已有的重复 Bean ——
 * 属于独立小重构，不该混在业务阶段做。已记入待办。</p>
 */
@Configuration
public class MybatisConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
