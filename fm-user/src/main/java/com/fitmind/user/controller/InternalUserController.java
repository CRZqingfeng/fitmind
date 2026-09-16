package com.fitmind.user.controller;

import com.fitmind.api.dto.user.UserBodyProfileDTO;
import com.fitmind.api.dto.user.UserDTO;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.user.domain.po.UserDetail;
import com.fitmind.user.domain.vo.BodyProfileVO;
import com.fitmind.user.service.IBodyProfileService;
import com.fitmind.user.service.IUserDetailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户内部查询接口（<b>免登录</b>）。
 *
 * <p><b>★ 为什么需要单独一个接口，而不是复用 {@code /users/{id}}</b>：
 * fm-user 的登录白名单是 {@code /users/**} —— 那个接口要求请求带 JWT。
 * 这在"用户查自己"的场景没问题，但<b>MQ 消费线程里根本没有用户上下文</b>：
 * fm-message 收到"退款到账"事件后要给用户发短信，需要查手机号，
 * 此时线程里没有登录态，调 {@code /users/{id}} 会 401
 * （实测日志：{@code FeignException$Unauthorized: [401] ... 请先登录}）。</p>
 *
 * <p>注意 {@code FeignOriginConfig} 帮不上忙：它只打一个
 * {@code request-from: feign} 标记头（用于让被调服务不要包装响应体），
 * <b>并不参与鉴权</b>。所以"服务间调用"在鉴权层面与"匿名请求"没有区别 ——
 * 想要免登录，就得把路径放在白名单之外。</p>
 *
 * <p>路径选 {@code /internal/**} 而不是 {@code /users/internal/**}：
 * 后者会被 {@code /users/**} 匹配到，照样要求登录。
 * <b>用路径前缀划出"不对外的接口"是这类问题的通用解法</b>。</p>
 */
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
@Tag(name = "用户内部接口（免登录，供服务间调用）")
public class InternalUserController {

    private final IUserDetailService detailService;
    private final IBodyProfileService bodyProfileService;

    @Operation(summary = "按 id 查询用户信息（免登录）")
    @Parameters(@Parameter(name = "id", description = "用户id"))
    @GetMapping("/{id}")
    public UserDTO queryUserById(@PathVariable("id") Long id) {
        UserDetail detail = detailService.queryById(id);
        if (detail == null) {
            throw new BizIllegalException("用户不存在：" + id);
        }
        UserDTO dto = new UserDTO();
        dto.setId(detail.getId());
        dto.setUsername(detail.getUsername());
        dto.setCellPhone(detail.getCellPhone());
        dto.setName(detail.getName());
        dto.setIcon(detail.getIcon());
        dto.setStatus(detail.getStatus());
        dto.setType(detail.getType() == null ? null : detail.getType().getValue());
        // ★ M5 体测评估需要的两个字段：
        //   性别决定体脂/腰臀比/基础代谢用哪套标准，生日用来算年龄（BMR 公式要）
        dto.setGender(detail.getGender());
        dto.setBirthday(detail.getBirthday());
        return dto;
    }

    /**
     * 查询用户身体档案（免登录）。
     *
     * <p>★ 免登录的理由同上：体测会在 MQ 消费 / 异步线程里被触发，
     * 那些线程没有用户上下文。</p>
     *
     * <p><b>返回 null 而不是空对象</b>：没填过档案和"档案里字段为空"是两件事，
     * 调用方（fm-exam 算 TDEE）必须能区分 —— 前者退回默认活动系数，
     * 后者说明数据有问题该排查。</p>
     */
    @Operation(summary = "按 id 查询身体档案（免登录）")
    @Parameters(@Parameter(name = "id", description = "用户id"))
    @GetMapping("/{id}/body-profile")
    public UserBodyProfileDTO queryBodyProfile(@PathVariable("id") Long id) {
        BodyProfileVO vo = bodyProfileService.getByUserId(id);
        if (vo == null) {
            return null;
        }
        return new UserBodyProfileDTO()
                .setUserId(id)
                .setHeight(vo.getHeight())
                .setWeight(vo.getWeight())
                .setTargetWeight(vo.getTargetWeight())
                .setWeeklyFrequency(vo.getWeeklyFrequency())
                .setTrainingLevel(vo.getTrainingLevel())
                .setInjuryHistory(vo.getInjuryHistory());
    }
}
