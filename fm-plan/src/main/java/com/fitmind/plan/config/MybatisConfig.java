package com.fitmind.plan.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 *
 * <p>⚠️ 不配分页插件，{@code page()} 会**查全表再内存截断** —— 接口看着正常、
 * 数据量一大就慢成谜（fm-learning 的 M2-1 踩过同一个坑）。</p>
 *
 * <p>与 fm-learning 的差别：那边还有一个 {@code DynamicTableNameInnerInterceptor}
 * 给积分榜按赛季分表用；fm-plan 没有分表，**不要顺手抄过来** ——
 * 动态表名拦截器一旦 handler 写错，会把所有同名的表一起换掉，排查成本极高。</p>
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
