package com.fitmind.exam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.exam.domain.po.BodyAssessment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 体测记录 Mapper。
 *
 * <p>没有自定义 SQL：体测的读写都是"按用户 + 时间"的普通查询，
 * MyBatis-Plus 的 Wrapper 够用。趋势查询在 Service 里查列表后做差即可
 * （一个人一辈子也就几百条体测记录，不需要在 SQL 里算窗口函数）。</p>
 */
@Mapper
public interface BodyAssessmentMapper extends BaseMapper<BodyAssessment> {
}
