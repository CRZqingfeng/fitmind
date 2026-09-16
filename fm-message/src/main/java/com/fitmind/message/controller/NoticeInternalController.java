package com.fitmind.message.controller;

import com.fitmind.api.dto.message.NoticeSendDTO;
import com.fitmind.message.service.INoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 定向通知内部接口（供其它服务调用）。
 *
 * <p><b>★ 为什么它叫"内部"接口</b>：它接受 {@code userId} 参数 ——
 * 也就是说"能给任意用户发通知"。这种能力绝不能对普通用户开放
 * （否则就是一对一的骚扰器）。网关不暴露它的对外文档，
 * 安全边界是"只有我们的服务会构造这个请求"。</p>
 *
 * <p><b>⚠️ 注意这条路径不是主要用法</b>：交易侧发通知走的是
 * <b>MQ 事件</b>（见 {@code mq/OrderRefundNoticeListener}），不是同步调这个接口。
 * 同步调用的三个问题（耦合、不可重放、无法多消费者）在 M4-4 方案里写过。
 * 这个接口留给"管理端手工给某个用户发一条"这类低频场景。</p>
 *
 * <p>幂等：请求里的 {@code sourceId} 会作为 {@code user_inbox} 的唯一键之一，
 * 重复调用不会重复通知 —— 调用方不需要自己去重。</p>
 *
 * <p>★ <b>请求体用的是 fm-api 里的契约 DTO</b>（不是本模块自己的 DTO）：
 * 调用方（fm-exam / 管理端）需要这个类型，放在被调方的私有模块里，
 * 调用方就只能照着 JSON 手拼 Map —— 拼错了编译器不管。</p>
 */
@Validated
@RestController
@RequestMapping("/internal/messages")
@RequiredArgsConstructor
@Tag(name = "定向通知（服务间调用）")
public class NoticeInternalController {

    private final INoticeService noticeService;

    @Operation(summary = "按模板给指定用户发通知")
    @PostMapping("/send")
    public void send(@RequestBody @Valid NoticeSendDTO dto) {
        noticeService.sendByTemplate(dto.getTemplateCode(), dto.getUserId(),
                dto.getParams(), dto.getSourceId());
    }
}
