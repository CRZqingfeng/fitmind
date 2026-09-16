package com.fitmind.learning.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.fitmind.learning.constants.LearningConstants;
import com.fitmind.learning.utils.TableInfoContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 分页插件。
 *
 * <p>⚠️ 不配这个插件的话，{@code page()} 会查全表——接口看着正常、数据是错的
 * （M2-1 踩过的坑：MP 3.5.9 起拦截器还从 extension 挪到了 jsqlparser 模块）。</p>
 */
@Configuration
public class MybatisConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 1.动态表名：积分榜按赛季分表（points_board -> points_board_{season}）
        //   表名从 TableInfoContext 取；取不到就返回原表名，保证普通查询不受影响
        DynamicTableNameInnerInterceptor dynamicTableName = new DynamicTableNameInnerInterceptor();
        dynamicTableName.setTableNameHandler((sql, tableName) -> {
            if (LearningConstants.POINTS_BOARD_TABLE.equals(tableName)) {
                String dynamic = TableInfoContext.getInfo();
                return dynamic != null ? dynamic : tableName;
            }
            return tableName;
        });
        interceptor.addInnerInterceptor(dynamicTableName);

        // 2.分页
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
