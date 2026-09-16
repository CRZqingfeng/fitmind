package com.fitmind.user.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.user.domain.po.User;
import com.fitmind.user.domain.po.UserDetail;
import com.fitmind.user.mapper.UserDetailMapper;
import com.fitmind.user.service.IUserDetailService;
import com.fitmind.user.service.IUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户详情服务实现。
 */
@Slf4j
@Service
public class UserDetailServiceImpl extends ServiceImpl<UserDetailMapper, UserDetail>
        implements IUserDetailService {

    private final IUserService userService;

    public UserDetailServiceImpl(@Lazy IUserService userService) {
        this.userService = userService;
    }

    @Override
    public UserDetail queryById(Long id) {
        UserDetail detail = getById(id);
        if (detail == null) {
            return null;
        }
        return fillAccountInfo(detail);
    }

    @Override
    public List<UserDetail> queryByIds(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptyList();
        }
        List<UserDetail> details = listByIds(ids);
        if (CollectionUtils.isEmpty(details)) {
            return Collections.emptyList();
        }
        // 批量查账号信息，避免 N+1
        List<User> users = userService.listByIds(ids);
        Map<Long, User> userMap = users.stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        details.forEach(d -> {
            User user = userMap.get(d.getId());
            if (user != null) {
                d.setCellPhone(user.getCellPhone())
                        .setUsername(user.getUsername())
                        .setStatus(user.getStatus() == null ? null : user.getStatus().getValue());
            }
        });
        return details;
    }

    private UserDetail fillAccountInfo(UserDetail detail) {
        User user = userService.getById(detail.getId());
        if (user != null) {
            detail.setCellPhone(user.getCellPhone())
                    .setUsername(user.getUsername())
                    .setStatus(user.getStatus() == null ? null : user.getStatus().getValue());
        }
        return detail;
    }
}
