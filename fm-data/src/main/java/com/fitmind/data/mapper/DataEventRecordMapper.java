package com.fitmind.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.fitmind.data.domain.po.DataEventRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 事件去重表 Mapper。
 *
 * <p>★ 这里没有 {@code insert} 方法 —— 用的是 MyBatis-Plus 的 {@code BaseMapper.insert}，
 * 但调用方必须<b>捕获唯一索引冲突</b>。见 {@code DataCollectServiceImpl#dedup}。</p>
 */
@Mapper
public interface DataEventRecordMapper extends BaseMapper<DataEventRecord> {

    /**
     * 统计某天某类事件的处理条数（自检 / 对账用）。
     *
     * <p>它能回答一个很实际的问题：<b>「今天到底收到了多少条签到消息？」</b>
     * 与 {@code data_daily.sign_in_count} 对比，就能看出有没有漏统计。</p>
     */
    @Select("""
            SELECT COUNT(*) FROM data_event_record
            WHERE event_type = #{eventType} AND stat_date = #{statDate}
            """)
    long countByTypeAndDate(@Param("eventType") String eventType,
                            @Param("statDate") Integer statDate);
}
