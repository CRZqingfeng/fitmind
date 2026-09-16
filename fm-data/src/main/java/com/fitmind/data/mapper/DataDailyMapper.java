package com.fitmind.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.fitmind.data.domain.po.DataDaily;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DataDailyMapper extends BaseMapper<DataDaily> {

    /**
     * 查最近 N 天的趋势（按日期升序，前端画图要正序）。
     *
     * @param startDate 起始日期 yyyyMMdd
     * @param limit     最多返回多少天
     */
    @Select("""
            SELECT * FROM data_daily
            WHERE stat_date >= #{startDate}
            ORDER BY stat_date ASC
            LIMIT #{limit}
            """)
    List<DataDaily> listTrend(@Param("startDate") Integer startDate, @Param("limit") int limit);

    @Select("""
            SELECT * FROM data_daily WHERE stat_date = #{statDate}
            """)
    DataDaily getByDate(@Param("statDate") Integer statDate);
}
