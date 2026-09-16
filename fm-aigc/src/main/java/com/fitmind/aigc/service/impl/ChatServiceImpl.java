package com.fitmind.aigc.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.fitmind.aigc.agent.AgentContext;
import com.fitmind.aigc.config.AigcProperties;
import com.fitmind.aigc.constants.AigcConstants;
import com.fitmind.aigc.domain.dto.ChatRequestDTO;
import com.fitmind.aigc.domain.po.ChatMessage;
import com.fitmind.aigc.domain.po.ChatSession;
import com.fitmind.aigc.domain.vo.ChatEventVO;
import com.fitmind.aigc.domain.vo.ChatResultVO;
import com.fitmind.aigc.orchestration.ChatOrchestrator;
import com.fitmind.aigc.orchestration.ChatType;
import com.fitmind.aigc.orchestration.OrchestrationResult;
import com.fitmind.aigc.service.IChatService;
import com.fitmind.aigc.service.IChatSessionService;
import com.fitmind.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话编排实现。
 *
 * <p>一趟对话的完整过程：<b>取会话 → 记提问 → 调模型（流式）→ 逐片吐事件
 * → 收尾落库 → 发 STOP</b>。M6-2 之后这里会插入 Agent 路由，
 * M6-3 会在 prompt 上挂 RAG Advisor，M6-5 会加上多轮历史与打断 ——
 * 但<b>这条主干的形状不变</b>。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements IChatService {

    private final ChatOrchestrator orchestrator;
    private final IChatSessionService sessionService;
    private final AigcProperties props;

    // ==================== 流式 ====================

    @Override
    public Flux<ChatEventVO> stream(ChatRequestDTO dto) {
        Long userId = UserContext.getUser();
        ChatTurn turn = prepare(dto, userId);

        // 编排策略：请求参数优先（演示与验收用），否则取配置默认值
        ChatType chatType = ChatType.parse(
                StrUtil.isNotBlank(dto.getChatType()) ? dto.getChatType() : props.getChat().getType());
        turn.chatType = chatType;
        // ⚠️ 这一步是"构造阶段"（同步执行），不是"订阅阶段" ——
        //    ROUTE 策略下它会真的去调一次路由模型。所以它抛出的异常
        //    **接不住**下面的 onErrorResume（那时流还没被订阅），
        //    必须在 orchestrate() 内部就地兜住。
        OrchestrationResult orchestration = orchestrate(chatType, turn);

        return orchestration.responses()
                // ★ 超时必须有：模型服务抖动时可能长时间不吐字，而 SSE 连接会一直挂着占线程。
                //   超时抛的是 java.util.concurrent.TimeoutException ——
                //   与 Mock 通道模拟的"模型侧超时"是同一个类型，所以两条路共用一个降级分支。
                .timeout(Duration.ofMillis(props.getChat().getTimeoutMs()))
                .map(resp -> onChunk(turn, resp))
                // 空分片不发：有些渠道会先发一个空的首包（只带 usage），
                // 前端收到空的 DATA 会渲染出一个看不见的空行
                .filter(ev -> StrUtil.isNotEmpty(ev.getData()))
                .concatWith(Flux.defer(() -> {
                    commit(turn, false);
                    return Flux.just(stopEvent(turn, false));
                }))
                .onErrorResume(e -> {
                    // ★ 降级不是"抛 500"，而是发一段可读的话 + 正常 STOP。
                    //   理由：模型超时/限流是 AI 场景里**预期内的业务状态**，
                    //   变成 500 前端只能弹"系统错误"，用户不知道能不能重试；
                    //   而且这条兜底回答会落库，复盘时能看到当时是真降级了。
                    log.error("模型调用失败，返回降级文案：requestId={}, provider={}",
                            turn.requestId, props.getChat().getProvider(), e);
                    turn.degraded = true;
                    turn.answer.append(AigcConstants.FALLBACK_MODEL_BUSY);
                    commit(turn, true);
                    return Flux.just(
                            ChatEventVO.data(AigcConstants.FALLBACK_MODEL_BUSY).setRequestId(turn.requestId),
                            stopEvent(turn, true));
                })
                .doFinally(signal -> {
                    // ★ 兜底落库：客户端断连时（用户关页面、点停止），上游会 cancel，
                    //   concatWith 里的 commit 根本不会执行 —— 但"已经生成的那部分"
                    //   必须存下来。否则用户看到一半没了，记录里也没有，
                    //   下次提问模型完全不知道刚才聊到哪。
                    //   （commit 内部是 CAS 幂等，正常路径重复调用安全。
                    //     常见实现在这里只存内容不删打断标记，而且标记没有 TTL ——
                    //     结果会话永久卡死，那是 M6-5 要修的问题。）
                    if (signal == SignalType.CANCEL) {
                        log.info("客户端提前断开，落库已生成内容：requestId={}, 已生成 {} 字",
                                turn.requestId, turn.answer.length());
                        commit(turn, turn.degraded);
                    }
                });
    }

    // ==================== 非流式 ====================

    @Override
    public ChatResultVO chat(ChatRequestDTO dto) {
        // ★ 非流式**不是"另写一遍流程"，而是流式的事件消费者**。
        //   理由是一个已经踩过的坑：M4-3 的优惠券有"推荐"和"下单"两个入口，
        //   当时下单那个入口图省事用了简化算法，于是"推荐时能减 110、下单时只减 100"，
        //   而且只在特定数据下才暴露，查了很久。
        //   这里提前把这条路堵掉：两个入口，一份实现。
        List<ChatEventVO> events = stream(dto)
                .collectList()
                // 给足超时缓冲：模型本身的超时由 chat.timeoutMs 控制，
                // 这里的 block 只是防止极端情况下把线程挂死
                .block(Duration.ofMillis(props.getChat().getTimeoutMs() + 5_000L));
        if (events == null) {
            return new ChatResultVO()
                    .setContent(AigcConstants.FALLBACK_MODEL_BUSY)
                    .setProvider(props.getChat().getProvider())
                    .setDegraded(true);
        }

        StringBuilder content = new StringBuilder();
        String metaJson = null;
        for (ChatEventVO ev : events) {
            if (AigcConstants.EVENT_DATA.equals(ev.getType())) {
                content.append(StrUtil.nullToEmpty(ev.getData()));
            } else if (AigcConstants.EVENT_STOP.equals(ev.getType())) {
                metaJson = ev.getData();
            }
        }
        ChatResultVO vo = new ChatResultVO()
                .setContent(content.toString())
                .setProvider(props.getChat().getProvider());
        if (StrUtil.isNotBlank(metaJson)) {
            var meta = JSONUtil.parseObj(metaJson);
            vo.setSessionId(meta.getStr("sessionId"))
                    .setRequestId(meta.getStr("requestId"))
                    .setAgentType(meta.getStr("agentType"))
                    .setChatType(meta.getStr("chatType"))
                    .setFirstTokenMs(meta.getInt("firstTokenMs"))
                    .setTotalMs(meta.getInt("totalMs"))
                    .setPromptTokens(meta.getInt("promptTokens"))
                    .setCompletionTokens(meta.getInt("completionTokens"))
                    .setRoutePromptTokens(meta.getInt("routePromptTokens"))
                    .setDegraded(meta.getBool("degraded", false));
        }
        return vo;
    }

    // ==================== 内部步骤 ====================

    /**
     * 准备一轮对话：取会话、生成 requestId、把提问落库。
     *
     * <p>★ 提问<b>在调模型之前</b>就落库，而不是等回答一起写。
     * 因为模型调用可能失败、可能被取消 —— 那些情况下问题也应该留在记录里
     * （用户确实问过），否则会话列表的次数和真实交互对不上。</p>
     */
    private ChatTurn prepare(ChatRequestDTO dto, Long userId) {
        ChatSession session = sessionService.getOrCreate(dto.getSessionId(), userId, dto.getContent());
        ChatTurn turn = new ChatTurn(session.getSessionId(), userId,
                IdUtil.getSnowflakeNextIdStr(), dto.getContent());

        // 提问与回答共用同一个 requestId —— 这是幂等兜底键的一半
        // （uk_request_role(request_id, role)），也是"停止当前这一轮"的定位依据
        sessionService.saveMessage(new ChatMessage()
                .setSessionId(session.getSessionId())
                .setUserId(userId)
                .setRequestId(turn.requestId)
                .setRole(AigcConstants.ROLE_USER)
                .setContent(dto.getContent()));
        sessionService.touch(session.getSessionId(), userId, 1);
        return turn;
    }

    /**
     * 分派给编排器，并记录"这次是谁回答的"。
     *
     * <p>★ 这里的 try-catch 不是防御性编程，而是因为<b>异常发生的位置特殊</b>：
     * 编排是在"构造 Flux"的阶段同步执行的（ROUTE 策略下会真的去调一次路由模型），
     * 此时流还没被订阅，后面的 {@code onErrorResume} 根本接不住这个异常 ——
     * 只会变成一个 500。所以在这里就地兜住，并把它转成 {@code Flux.error}
     * 交给降级链路去统一处理（用户看到的还是那句可读的降级文案，不是 500）。</p>
     */
    private OrchestrationResult orchestrate(ChatType chatType, ChatTurn turn) {
        AgentContext context = new AgentContext(
                turn.sessionId, turn.requestId, turn.userId, turn.question);
        try {
            OrchestrationResult result = orchestrator.dispatch(chatType, context);
            turn.agentLabel = result.label();
            // ★ 把编排动作的 token 开销也记进这一轮：ROUTE 策略的花费 = 路由 + 回答，
            //   只记后者会让这个策略的成本被系统性低估一次模型调用
            turn.routePromptTokens = result.extraPromptTokens();
            turn.routeCompletionTokens = result.extraCompletionTokens();
            return result;
        } catch (Exception e) {
            log.error("编排失败（chatType={}），转由降级链路处理", chatType, e);
            turn.agentLabel = "failed";
            // 显式给泛型参数：Flux.error 单独用会被推断成 Flux<Object>
            return OrchestrationResult.of(null, Flux.<ChatResponse>error(e));
        }
    }

    /** 处理一个模型分片：记首字延迟、累计 token、包成 DATA 事件 */
    private ChatEventVO onChunk(ChatTurn turn, ChatResponse resp) {
        if (turn.firstTokenMs < 0) {
            // ★ 首字延迟要在"第一个分片到达"时记，不是等回答结束。
            //   这两个指标回答的是不同问题：首字延迟决定"打字机什么时候开始动"，
            //   总耗时决定"用户要等多久"，优化手段完全不同。
            turn.firstTokenMs = (int) (System.currentTimeMillis() - turn.startMs);
        }
        Usage usage = resp.getMetadata() == null ? null : resp.getMetadata().getUsage();
        if (usage != null) {
            turn.promptTokens = usage.getPromptTokens();
            turn.completionTokens = usage.getCompletionTokens();
        }
        String text = resp.getResult() == null || resp.getResult().getOutput() == null
                ? "" : StrUtil.nullToEmpty(resp.getResult().getOutput().getText());
        turn.answer.append(text);
        return ChatEventVO.data(text).setRequestId(turn.requestId);
    }

    /**
     * 落库助手消息 + 刷新会话。
     *
     * <p>★ CAS 幂等：正常收尾、降级、客户端断连三条路径都会调用它，
     * 但只有第一条真正写库。数据库层还有 {@code uk_request_role} 兜底
     * （多实例部署时 CAS 只在本进程内有效，唯一索引才是跨进程的保证）——
     * <b>应用层的乐观锁省一次无用请求，数据库层的约束才是正确性保证</b>，
     * 两者分工不同，不能只留一个。</p>
     */
    private void commit(ChatTurn turn, boolean degraded) {
        if (!turn.committed.compareAndSet(false, true)) {
            return;
        }
        try {
            ChatMessage msg = new ChatMessage()
                    .setSessionId(turn.sessionId)
                    .setUserId(turn.userId)
                    .setRequestId(turn.requestId)
                    .setRole(AigcConstants.ROLE_ASSISTANT)
                    .setContent(turn.answer.toString())
                    .setAgentType(turn.agentLabel)
                    .setProvider(props.getChat().getProvider())
                    .setPromptTokens(turn.totalPromptTokens() == 0 ? null : turn.totalPromptTokens())
                    .setCompletionTokens(turn.totalCompletionTokens() == 0 ? null : turn.totalCompletionTokens())
                    .setFirstTokenMs(turn.firstTokenMs < 0 ? null : turn.firstTokenMs)
                    .setTotalMs((int) (System.currentTimeMillis() - turn.startMs));
            sessionService.saveMessage(msg);
            sessionService.touch(turn.sessionId, turn.userId, 1);
            log.info("本轮对话完成：requestId={}, 编排={}, Agent={}, 首字 {}ms, 总耗时 {}ms, tokens={}/{}, 降级={}",
                    turn.requestId, turn.chatType, turn.agentLabel, turn.firstTokenMs, msg.getTotalMs(),
                    turn.promptTokens, turn.completionTokens, degraded);
        } catch (Exception e) {
            // 落库失败不能影响用户已经看到的回答 —— 回答已经吐出去了，
            // 这里抛异常只会让前端收到一个错误，而内容其实是对的
            log.error("对话落库失败（回答已产出，不影响用户）：requestId={}", turn.requestId, e);
        }
    }

    /**
     * 收尾事件：STOP + 本轮指标。
     *
     * <p>★ 为什么把指标放在 STOP 的载荷里（而不是只落库）：
     * ① 前端可以显示"本次回答耗时 1.2s"、可以对慢回答做提示；
     * ② 非流式接口因此可以**完全复用流式的实现** —— 它只要从 STOP 里读指标就行，
     *    不用自己再算一遍（也就不会算出不一样的结果）。</p>
     */
    private ChatEventVO stopEvent(ChatTurn turn, boolean degraded) {
        return ChatEventVO.stop()
                .setRequestId(turn.requestId)
                .setData(JSONUtil.createObj()
                        .set("sessionId", turn.sessionId)
                        .set("requestId", turn.requestId)
                        // ★ 把"谁回答的"和"用了哪种编排"一起给前端：
                        //   前端可以据此显示"由课程顾问回答"这类标注，
                        //   而在演示三种编排策略的对比时，这两个字段也是最直观的区分
                        .set("agentType", turn.agentLabel)
                        .set("chatType", turn.chatType.name())
                        .set("firstTokenMs", turn.firstTokenMs < 0 ? null : turn.firstTokenMs)
                        .set("totalMs", (int) (System.currentTimeMillis() - turn.startMs))
                        // token 是**本轮总量**（回答 + 编排动作）——
                        // 使用者关心的是"这一轮花了多少"，不是"最后一次调用花了多少"
                        .set("promptTokens", turn.totalPromptTokens() == 0 ? null : turn.totalPromptTokens())
                        .set("completionTokens", turn.totalCompletionTokens() == 0 ? null : turn.totalCompletionTokens())
                        // 另给一份明细：ROUTE 策略下路由单独花了多少（对比三种策略时要看这个）
                        .set("routePromptTokens", turn.routePromptTokens)
                        .set("routeCompletionTokens", turn.routeCompletionTokens)
                        .set("degraded", degraded)
                        .toString());
    }

    /** 一轮对话的运行时状态（可变，专属于某一次请求，不存在共享问题） */
    private static final class ChatTurn {
        private final String sessionId;
        private final Long userId;
        private final String requestId;
        private final String question;
        private final long startMs = System.currentTimeMillis();
        private final StringBuilder answer = new StringBuilder();
        private final AtomicBoolean committed = new AtomicBoolean(false);
        private int firstTokenMs = -1;
        private Integer promptTokens;
        private Integer completionTokens;
        private volatile boolean degraded;
        /** 本次使用的编排策略 */
        private ChatType chatType = ChatType.DEFAULT;
        /** 实际产出回答的 Agent 标签（SIMPLE 策略下是 simple） */
        private String agentLabel = "simple";
        /** ★ 编排动作（ROUTE 策略下的路由调用）额外消耗的 token */
        private int routePromptTokens;
        private int routeCompletionTokens;

        /** 本轮总输入 token = 回答那次调用 + 编排动作 */
        private int totalPromptTokens() {
            return (promptTokens == null ? 0 : promptTokens) + routePromptTokens;
        }

        /** 本轮总输出 token */
        private int totalCompletionTokens() {
            return (completionTokens == null ? 0 : completionTokens) + routeCompletionTokens;
        }

        private ChatTurn(String sessionId, Long userId, String requestId, String question) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.requestId = requestId;
            this.question = question;
        }
    }
}
