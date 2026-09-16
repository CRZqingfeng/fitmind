package com.fitmind.media.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * <p><b>分页插件必须显式注册</b>，否则 {@code ServiceImpl.page()} 不会改写 SQL，
 * 结果是「查全表 + total 不准」——接口看着正常，数据却是错的，这种 bug 很隐蔽。</p>
 *
 * <p>M1 的 fm-user 没用分页所以没配，这里首次引入。
 * 后续每个用到分页的模块都要有这个配置类。</p>
 */
@Configuration
public class MybatisConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 指定数据库类型，MP 才能生成正确的分页方言
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
