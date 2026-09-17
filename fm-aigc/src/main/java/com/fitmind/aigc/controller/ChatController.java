package com.fitmind.aigc.controller;

import com.fitmind.aigc.config.AigcProperties;
import com.fitmind.aigc.domain.dto.ChatRequestDTO;
import com.fitmind.aigc.domain.dto.ChatStopDTO;
import com.fitmind.aigc.domain.vo.ChatEventVO;
import com.fitmind.aigc.domain.vo.ChatMessageVO;
import com.fitmind.aigc.domain.vo.ChatResultVO;
import com.fitmind.aigc.domain.vo.ChatSessionVO;
import com.fitmind.aigc.prompt.PromptManager;
import com.fitmind.aigc.service.IChatService;
import com.fitmind.aigc.service.IChatSessionService;
import com.fitmind.common.domain.dto.PageDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 教练对话入口。
 *
 * <p>用户身份一律从 {@code UserContext} 取（登录拦截器保证它在），
 * 请求体里没有 userId —— 这个"不接收"本身就是一层越权防护。</p>
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
@Tag(name = "AI 教练")
public class ChatController {

    /** SSE 文本类型的字符集必须写死：
     *  不写的话 StringHttpMessageConverter 可能按平台默认编码处理，中文会变问号。 */
    private static final MediaType TEXT_PLAIN_UTF8 = MediaType.parseMediaType("text/plain;charset=UTF-8");

    private final IChatService chatService;
    private final IChatSessionService sessionService;
    private final PromptManager promptManager;
    private final AigcProperties props;

    @Operation(summary = "流式对话（SSE：DATA 增量文本 / PARAM 卡片参数 / STOP 结束）")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody @Valid ChatRequestDTO dto) {
        // SSE 连接的超时要比模型超时长一点：模型超时时我们要能在连接里
        // 发一条降级文案 + STOP，而不是让浏览器先看到连接断掉
        SseEmitter emitter = new SseEmitter((long) props.getChat().getTimeoutMs() + 10_000L);
        AtomicReference<Disposable> upstream = new AtomicReference<>();

        upstream.set(chatService.stream(dto).subscribe(
                ev -> send(emitter, ev),
                err -> {
                    log.error("SSE 流异常终止", err);
                    safeCompleteWithError(emitter, err);
                },
                emitter::complete));

        // ★ 客户端断开必须在上游 cancel —— Reactor 不会自己知道 TCP 连接没了。
        //   cancel 之后 ChatServiceImpl 的 doFinally(SignalType.CANCEL) 会触发，
        //   把"用户已经看到的那部分内容"落库。
        //   （常见实现正是在这里漏了清理打断标记，导致会话永久卡死 — M6-5 会专门修那条链路。）
        emitter.onCompletion(() -> dispose(upstream));
        emitter.onTimeout(() -> {
            log.warn("SSE 连接超时，主动关闭");
            dispose(upstream);
            emitter.complete();
        });
        emitter.onError(e -> dispose(upstream));
        return emitter;
    }

    @Operation(summary = "非流式对话（一次返回完整回答与本轮指标）")
    @PostMapping
    public ChatResultVO chat(@RequestBody @Valid ChatRequestDTO dto) {
        return chatService.chat(dto);
    }

    /**
     * 停止本会话正在生成的一轮。
     *
     * <p>★ <b>为什么需要一个独立的接口，而不是"客户端断开连接就够了"</b>：
     * 断开只能停下<b>本实例</b>这条流。多实例部署下，点停止的请求会被负载均衡
     * 打到另一个实例 —— 那边没有这条流可断，只能通过共享的 Redis 标记
     * 通知真正在生成的那个实例。这就是"分布式打断"与"本机 cancel"的区别。</p>
     *
     * <p>返回被停止的 requestId；返回 null 表示该会话当前没有在生成的轮次
     * （用户点了一个已经结束的停止按钮 —— 正常情况，不报错，
     * <b>因为"停止"是一个幂等愿望而不是一条指令</b>）。</p>
     */
    @Operation(summary = "停止本会话正在生成的一轮（分布式打断）")
    @PostMapping("/stop")
    public String stop(@RequestBody @Valid ChatStopDTO dto) {
        return chatService.stop(dto.getSessionId());
    }

    /**
     * 某个会话的"上下文窗口体检报告"。
     *
     * <p>★ 排查"它怎么不记得刚才说的了"这类问题的第一站：
     * 不用再加一轮对话去试探，一行请求就能看到摘要、条数、TTL 与最近几条预览。</p>
     */
    @Operation(summary = "会话的上下文窗口（摘要 / 条数 / TTL / 最近几条预览）")
    @GetMapping("/sessions/{sessionId}/memory")
    public Map<String, Object> memory(@PathVariable String sessionId) {
        return chatService.memoryView(sessionId);
    }

    /**
     * 我的今日配额。
     *
     * <p>★ "还能问几次"是个用户会关心的问题（尤其在被拒过一次之后）。
     * 提前显示它，比让用户在提问时突然被挡住要好得多。</p>
     */
    @Operation(summary = "我的今日 AI 对话配额（剩余次数 / 已用 token / 正在生成）")
    @GetMapping("/quota")
    public Map<String, Object> quota() {
        return chatService.quota();
    }

    @Operation(summary = "我的会话列表")
    @GetMapping("/sessions")
    public PageDTO<ChatSessionVO> sessions(@RequestParam(defaultValue = "1") int pageNo,
                                           @RequestParam(defaultValue = "20") int pageSize) {
        return sessionService.pageMine(pageNo, pageSize);
    }

    @Operation(summary = "某个会话的消息历史（卡片数据会一并返回，便于历史重渲染）")
    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatMessageVO> messages(@PathVariable String sessionId) {
        return sessionService.listMessages(sessionId);
    }

    /**
     * 当前模型通道。
     *
     * <p>★ 看起来是个"没必要的接口"，但排查问题时它排在第一位：
     * "为什么回答怪怪的" / "为什么检索好像没生效" —— 第一件要确认的事就是
     * <b>到底走的哪个通道</b>（Mock 还是真实模型）。有了这个接口，
     * 一行 curl 就能确定，不用去翻日志或配置。</p>
     */
    @Operation(summary = "当前使用的模型通道")
    @GetMapping("/provider")
    public String provider() {
        return props.getChat().getProvider();
    }

    /**
     * 各提示词的当前来源。
     *
     * <p>★ 只返回"来源 + 长度"，<b>不返回提示词内容</b>：提示词里写着业务规则
     * （什么时候该引导下单、安全边界在哪），那是内部资产，不该给任何登录用户看到。</p>
     *
     * <p>它解决的问题很具体：<b>"我改了 Nacos，怎么没生效？"</b>
     * 可能是 dataId 拼错、group 不对、配置没发布、改错了环境……有一行
     * "当前实际用的是哪一份"的查询，就不用靠猜。</p>
     */
    @Operation(summary = "各提示词的当前来源（Nacos / 本地文件 / 代码兜底）")
    @GetMapping("/prompts")
    public Map<String, String> prompts() {
        return promptManager.describe();
    }

    // ==================== 内部 ====================

    private void send(SseEmitter emitter, ChatEventVO ev) {
        try {
            emitter.send(SseEmitter.event()
                    .name(ev.getType())
                    // null 会被当作"不发 data"，前端就区分不出"空分片"和"事件丢失"，
                    // 所以这里统一转成空串
                    .data(ev.getData() == null ? "" : ev.getData(), TEXT_PLAIN_UTF8));
        } catch (IOException | IllegalStateException e) {
            // 客户端断开（关页面 / 点停止）会走到这里。
            // 抛出去让 Reactor 感知并 cancel 上游 —— 这是"已生成内容落库"的触发条件。
            throw new IllegalStateException("SSE 连接已不可用：" + e.getMessage(), e);
        }
    }

    private void safeCompleteWithError(SseEmitter emitter, Throwable err) {
        try {
            emitter.completeWithError(err);
        } catch (Exception ignore) {
            // 连接早就断了，这里再报错没有意义
        }
    }

    private void dispose(AtomicReference<Disposable> ref) {
        Disposable d = ref.get();
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
    }
}
