package com.fitmind.learning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.learning.domain.po.PointsRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 积分明细 Mapper。
 */
@Mapper
public interface PointsRecordMapper extends BaseMapper<PointsRecord> {
}
