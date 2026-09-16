package com.fitmind.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.user.domain.po.UserDetail;

import java.util.List;

/**
 * 用户详情服务。
 */
public interface IUserDetailService extends IService<UserDetail> {

    /**
     * 按用户 id 查询详情，并补齐账号表里的手机号、用户名、状态
     */
    UserDetail queryById(Long id);

    /**
     * 批量查询用户详情
     */
    List<UserDetail> queryByIds(List<Long> ids);
}
