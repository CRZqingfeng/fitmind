package com.fitmind.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.user.domain.po.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户账号 Mapper。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
