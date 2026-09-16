package com.fitmind.aigc.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：<b>分页插件必须显式注册</b>。
 *
 * <p>★ 漏了它的表现是"不报错的错"，而且非常容易被忽略：
 * {@code selectPage()} 不会改写 SQL，于是<b>查全表、total 恒为 0</b>。
 * 接口返回 200、list 里有数据，看起来一切正常 ——
 * 只有把 total 打出来、或者数据量大到查不动的时候才会发现。</p>
 *
 * <p>本项目实际踩到了：M6-1 先写完接口，测会话列表时看到
 * {@code total=0} 但 list 有 5 条，才想起少了这个配置。</p>
 *
 * <p>⚠️ 目前的现状是"每个用到分页的模块各配一份"
 * （fm-course / fm-learning / fm-media / 本模块）。
 * 四份完全相同的代码说明它<b>该被提取到 fm-common 的自动装配里</b> ——
 * 之所以还没做：那会一次性影响 14 个已验收的服务
 * （而且已配的三个模块会出现重复 Bean，得先删旧的），
 * 属于独立的小重构，不该混在业务阶段里做。已记入待办。</p>
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
