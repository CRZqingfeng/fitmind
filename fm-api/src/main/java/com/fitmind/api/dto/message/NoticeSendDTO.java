package com.fitmind.api.dto.message;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Map;

/**
 * 定向通知请求（服务间契约）。
 *
 * <p>放在 fm-api 而不是 fm-message 内部：<b>调用方需要它</b>。
 * 契约类型放在被调方的私有模块里，调用方就只能"照着 JSON 手拼一个 Map"，
 * 拼错了编译器不管 —— 这正是 M4-4 里"验签参数两边必须严格对齐"那类坑的温床。</p>
 */
@Data
@Accessors(chain = true)
@Schema(description = "定向通知请求")
public class NoticeSendDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "接收用户id")
    @NotNull(message = "用户id不能为空")
    private Long userId;

    @Schema(description = "通知模板代号（如 refund-success）")
    @NotBlank(message = "模板代号不能为空")
    private String templateCode;

    @Schema(description = "模板参数（用于替换 {占位符}）")
    private Map<String, Object> params;

    /**
     * ★ 业务id（幂等键）。
     *
     * <p>调用方传"这次通知是哪笔业务触发的"。
     * {@code user_inbox} 上的 {@code uk_user_source} 会挡住重复写入，
     * 所以<b>调用方不必保证只调一次</b> —— 在一个会重试的分布式系统里，
     * 这个性质比"调用方小心一点"可靠得多。</p>
     */
    @Schema(description = "业务id（幂等键）")
    @NotNull(message = "业务id不能为空")
    private Long sourceId;
}
