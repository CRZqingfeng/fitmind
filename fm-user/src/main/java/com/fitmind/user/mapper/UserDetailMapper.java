package com.fitmind.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.user.domain.po.UserDetail;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户详情 Mapper。
 */
@Mapper
public interface UserDetailMapper extends BaseMapper<UserDetail> {
}
