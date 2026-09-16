package com.fitmind.api.client.user.fallback;

import com.fitmind.api.client.user.UserClient;
import com.fitmind.api.dto.user.CoachDTO;
import com.fitmind.api.dto.user.LoginFormDTO;
import com.fitmind.api.dto.user.UserBodyProfileDTO;
import com.fitmind.api.dto.user.UserDTO;
import com.fitmind.common.domain.dto.LoginUserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * {@link UserClient} 的降级工厂。
 *
 * <p>当 fm-user 服务不可用（超时、熔断）时，返回兜底值而不是把异常抛给调用方，
 * 保证调用链路上游不会整体雪崩。</p>
 *
 * <p>标注 {@code @ConditionalOnClass}：本模块可能被<b>不使用 Feign 的服务</b>依赖，
 * 此时应跳过本组件，避免因为找不到 Feign 的接口而启动失败。</p>
 */
@Slf4j
@Component
@ConditionalOnClass(FallbackFactory.class)
public class UserClientFallback implements FallbackFactory<UserClient> {

    @Override
    public UserClient create(Throwable cause) {
        return new UserClient() {
            @Override
            public LoginUserDTO queryUserDetail(LoginFormDTO loginDTO, boolean isStaff) {
                log.error("用户服务不可用，无法完成登录校验。原因：{}", cause.getMessage());
                return null;
            }

            @Override
            public Long exchangeUserIdWithPhone(String phone) {
                log.error("用户服务不可用，无法根据手机号查询用户。原因：{}", cause.getMessage());
                return null;
            }

            @Override
            public UserDTO queryUserById(Long id) {
                log.error("用户服务不可用，无法查询用户。id={}，原因：{}", id, cause.getMessage());
                return null;
            }

            @Override

            public UserBodyProfileDTO queryBodyProfile(Long id) {

                log.error("用户服务不可用，无法查询身体档案。id={}，原因：{}", id, cause.getMessage());

                // ★ 返回 null：体测会退回"久坐"系数而不是算出一个偏低的 TDEE。

                //   宁可给一个保守的默认值，也不要假装拿到了用户档案

                return null;

            }


            @Override
            public UserDTO queryUserInternal(Long id) {
                // ★ 返回 null 而不是抛异常：调用方（fm-message）会把它当成
                //   "拿不到手机号 → 跳过短信"，而站内信照常发出。
                //   短信是锦上添花，不该因为它失败就让整条通知链路断掉
                log.error("用户服务不可用，无法查询用户（内部接口）。id={}，原因：{}", id, cause.getMessage());
                return null;
            }

            @Override
            public List<UserDTO> queryUserByIds(List<Long> ids) {
                log.error("用户服务不可用，无法批量查询用户。原因：{}", cause.getMessage());
                return Collections.emptyList();
            }

            @Override
            public Integer queryUserType(Long id) {
                log.error("用户服务不可用，无法查询用户类型。id={}，原因：{}", id, cause.getMessage());
                return null;
            }

            @Override
            public List<CoachDTO> queryCoaches(List<Long> ids) {
                log.error("用户服务不可用，无法查询教练信息。原因：{}", cause.getMessage());
                return Collections.emptyList();
            }
        };
    }
}
