package com.fitmind.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.message.domain.po.UserInbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 用户信箱 Mapper。
 */
@Mapper
public interface UserInboxMapper extends BaseMapper<UserInbox> {

    /**
     * 未读消息数（未过期的）。
     *
     * <p>小程序端红点要高频调这个接口，所以让它走
     * {@code idx_user_read_time(user_id, is_read, push_time)} 索引 ——
     * 这是个典型的"覆盖索引计数"，只扫索引不回表。</p>
     */
    @Select("""
            SELECT COUNT(*) FROM user_inbox
            WHERE user_id = #{userId} AND is_read = 0
              AND (expire_time IS NULL OR expire_time > #{now})
            """)
    int countUnread(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * 该用户<b>已展开过的最后一条公告</b>的发布时间。
     *
     * <p>用于确定"本次拉取要从哪一刻之后的公告开始展开"，避免每次都把
     * 历史公告全捞一遍。</p>
     *
     * <p>★ <b>注意它只是性能优化，不是正确性依赖</b>：
     * 常见实现把 {@code push_time} 当作唯一的去重依据，于是同一毫秒的两条公告
     * 会漏掉或重复。我们的正确性由 {@code uk_user_source} 唯一索引保证 ——
     * 就算这里算出的时间点不对，重复展开的那条也会被索引挡住。
     * <b>优化可以不准，正确性不能靠优化。</b></p>
     */
    @Select("""
            SELECT MAX(push_time) FROM user_inbox
            WHERE user_id = #{userId} AND source_type = #{sourceType}
            """)
    LocalDateTime queryLatestExpandTime(@Param("userId") Long userId,
                                        @Param("sourceType") int sourceType);
}
