package com.fitmind.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.message.domain.po.PublicNotice;
import org.apache.ibatis.annotations.Mapper;

/**
 * 全员公告 Mapper。
 *
 * <p>没有自定义 SQL：公告的读写都是最普通的 CRUD ——
 * "发布一行、按时间范围查一批"，MyBatis-Plus 的 Wrapper 已经够用，</p>
 * <p>写自定义 SQL 反而多一处要维护的地方。</p>
 */
@Mapper
public interface PublicNoticeMapper extends BaseMapper<PublicNotice> {
}
