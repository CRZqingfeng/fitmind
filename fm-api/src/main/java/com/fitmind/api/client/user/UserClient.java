package com.fitmind.api.client.user;

import com.fitmind.api.client.user.fallback.UserClientFallback;
import com.fitmind.api.dto.user.CoachDTO;
import com.fitmind.api.dto.user.LoginFormDTO;
import com.fitmind.api.dto.user.UserBodyProfileDTO;
import com.fitmind.api.dto.user.UserDTO;
import com.fitmind.common.domain.dto.LoginUserDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户服务 Feign 客户端。
 *
 * <p>供其他微服务调用 fm-user。注意：这里的返回类型是<b>领域对象</b>而非 {@code R}，
 * 因为服务间调用不会被 {@code WrapperResponseBodyAdvice} 包装。</p>
 */
@FeignClient(value = "fm-user", fallbackFactory = UserClientFallback.class)
public interface UserClient {

    /**
     * 登录校验：查询并校验用户信息
     *
     * @param loginDTO 登录表单
     * @param isStaff  是否为管理端登录
     * @return 登录用户信息（含 userId、roleId）
     */
    @PostMapping("/users/detail/{isStaff}")
    LoginUserDTO queryUserDetail(@RequestBody LoginFormDTO loginDTO, @PathVariable("isStaff") boolean isStaff);

    /**
     * 根据手机号查询用户 id
     */
    @GetMapping("/users/ids")
    Long exchangeUserIdWithPhone(@RequestParam("phone") String phone);

    /**
     * 根据 id 查询用户
     */
    @GetMapping("/users/{id}")
    UserDTO queryUserById(@PathVariable("id") Long id);

    /**
     * 根据 id 查询用户（<b>免登录内部接口</b>）。
     *
     * <p><b>★ 什么时候必须用它</b>：调用发生在<b>没有用户上下文</b>的线程里 ——
     * 最典型的是 MQ 消费者。此时 {@code /users/{id}} 会返回 401
     * （那条路径在 fm-user 的登录白名单里），而本方法指向的
     * {@code /internal/users/{id}} 不在白名单，可以匿名调用。</p>
     *
     * <p>实测踩坑记录：fm-message 收到"退款到账"事件后要查手机号发短信，
     * 用 {@code queryUserById} 报
     * {@code FeignException$Unauthorized: [401] during [GET] to [http://fm-user/users/1]}，
     * 导致短信静默不发出（站内信正常，因为不需要手机号）。</p>
     */
    @GetMapping("/internal/users/{id}")
    UserDTO queryUserInternal(@PathVariable("id") Long id);

    /**
     * 查询用户身体档案（<b>免登录内部接口</b>）。
     *
     * <p><b>★ 为什么体测要专门取这个</b>：{@code weeklyFrequency} 决定 TDEE 的活动系数。
     * 拿不到就只能退回"久坐 1.2"—— 对一个每周练 4 次的人来说，
     * TDEE 会低估约 25%（≈500 kcal/天），而推荐摄入量正是建立在 TDEE 上的。</p>
     *
     * <p>用户没填过档案时返回 <b>null</b>（而不是一个全空的对象）：
     * 调用方必须区分"没填过"和"填了但值为空"，前者该退回默认系数，
     * 后者说明数据有问题。</p>
     */
    @GetMapping("/internal/users/{id}/body-profile")
    UserBodyProfileDTO queryBodyProfile(@PathVariable("id") Long id);

    /**
     * 根据 id 批量查询用户
     */
    @GetMapping("/users/list")
    List<UserDTO> queryUserByIds(@RequestParam("ids") List<Long> ids);

    /**
     * 查询用户类型：1-管理员，2-学员，3-教练
     */
    @GetMapping("/users/{id}/type")
    Integer queryUserType(@PathVariable("id") Long id);

    /**
     * 批量查询教练信息（姓名、头像、简介、形象照）。
     *
     * <p>课程详情页要展示教练，而简介和形象照在 user_detail 表里，
     * 普通的 {@code /users/list} 拿不到，所以单独开了这个接口。</p>
     */
    @GetMapping("/users/coaches")
    List<CoachDTO> queryCoaches(@RequestParam("ids") List<Long> ids);
}
