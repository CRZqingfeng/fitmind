package com.fitmind.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.auth.domain.po.LoginRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 登录记录 Mapper。
 */
@Mapper
public interface LoginRecordMapper extends BaseMapper<LoginRecord> {
}
