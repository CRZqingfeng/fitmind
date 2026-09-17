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
import com.fitmind.aigc.interrupt.GenerationRegistry;
import com.fitmind.aigc.memory.CompressOutcome;
import com.fitmind.aigc.memory.ConversationMemory;
import com.fitmind.aigc.orchestration.ChatOrchestrator;
import com.fitmind.aigc.orchestration.ChatType;
import com.fitmind.aigc.orchestration.OrchestrationResult;
import com.fitmind.aigc.rag.RagTrace;
import com.fitmind.aigc.service.BodyProfileService;
import com.fitmind.aigc.service.IChatService;
import com.fitmind.aigc.service.IChatSessionService;
import com.fitmind.aigc.tool.ToolTrace;
import com.fitmind.api.dto.user.UserBodyProfileDTO;
import com.fitmind.common.utils.TokenContext;
import com.fitmind.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    /** ★ 体测档案：编排层拉一次，RAG 元数据过滤与 BodyTools 共用同一份 */
    private final BodyProfileService bodyProfileService;
    /** ★ 会话记忆（M6-5）：上下文窗口的读写与压缩，对话主干只跟它打交道 */
    private final ConversationMemory memory;
    /** ★ 生成登记簿（M6-5）：分布式打断的标记，见 GenerationRegistry */
    private final GenerationRegistry generationRegistry;

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
                // ★★ M6-5 分布式打断：每个分片之前检查一次"这一轮还算数吗"。
                //
                //   takeWhile 的语义正好合用：谓词返回 false 时**取消上游并正常结束**，
                //   于是后面 concatWith 里的 commit 与 STOP 照常执行 ——
                //   用户看到的是"打字机停住 + 本轮正常收尾"，而不是一个断掉的连接。
                //
                //   ⚠️ 为什么"被停止"要在这里检测，而不是靠客户端断连：
                //   客户端断连只能停下**本实例**的这条流。而真实的多实例部署下，
                //   "点停止"的请求会被负载均衡打到**另一个实例** ——
                //   那条路径必须通过共享的 Redis 标记传过来，这也是它存在的全部理由。
                .takeWhile(ev -> !interrupted(turn))
                // 空分片不发：有些渠道会先发一个空的首包（只带 usage），
                // 前端收到空的 DATA 会渲染出一个看不见的空行
                .filter(ev -> StrUtil.isNotEmpty(ev.getData()))
                .concatWith(Flux.defer(() -> {
                    commit(turn, false);
                    // ★ 顺序有讲究：引用来源（PARAM）在正文之后、STOP 之前发。
                    //   前端先渲染完回答、再挂上"参考资料"卡片，不会打断打字机；
                    //   而 STOP 是"本轮结束"的信号，必须留在最后 ——
                    //   前端收到 STOP 就关连接，放在它后面的事件会被丢掉
                    return Flux.fromIterable(tailEvents(turn, false));
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
                    // 降级路径也带上引用来源：检索可能已经成功了，
                    // "回答没出来但依据是有的"这个信息对排查很有价值
                    return Flux.fromIterable(tailEvents(turn, true))
                            .startWith(ChatEventVO.data(AigcConstants.FALLBACK_MODEL_BUSY)
                                    .setRequestId(turn.requestId));
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
                    // ★★ M6-5 打断标记的清理 —— **挂在 doFinally 上，覆盖全部四种信号**
                    //   （正常完成 / 出错 / 取消 / 被停止）。
                    //
                    //   常见实现只在"正常结束"这一条路径上清（而且用的是单机 Map），
                    //   于是任何异常路径都会把标记留下来 → 会话永久卡死。
                    //   这里再加一道 TTL 兜底：就算这个 finally 因为进程被杀而没跑，
                    //   标记也会在 120 秒后自动消失 —— **"状态"变成了"租约"**。
                    //
                    //   ⚠️ 清理是"值相等才删"（Lua）：避免把同会话下一轮已经打上的
                    //   新标记误删（那会让新一轮的流莫名其妙自己停掉）。
                    generationRegistry.clear(turn.sessionId, turn.requestId);
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
        String paramJson = null;
        for (ChatEventVO ev : events) {
            if (AigcConstants.EVENT_DATA.equals(ev.getType())) {
                content.append(StrUtil.nullToEmpty(ev.getData()));
            } else if (AigcConstants.EVENT_STOP.equals(ev.getType())) {
                metaJson = ev.getData();
            } else if (AigcConstants.EVENT_PARAM.equals(ev.getType())) {
                // ★ PARAM（引用来源）也在这里被消费 —— 非流式接口因此**天然带回**了
                //   引用信息，而不是"流式有、非流式没有"。这就是"两个入口一份实现"的好处：
                //   不用记得在两条路径上各加一次，也就不会漏（M4-3 的两个入口就是这么漂移的）。
                paramJson = ev.getData();
            }
        }
        ChatResultVO vo = new ChatResultVO()
                .setContent(content.toString())
                .setProvider(props.getChat().getProvider());
        if (StrUtil.isNotBlank(paramJson)) {
            // PARAM 的载荷是 {rag:{...}, cards:[...]} —— 两种结构化数据共用一个事件
            var param = JSONUtil.parseObj(paramJson);
            if (param.containsKey("rag")) {
                vo.setRag(JSONUtil.parseObj(param.get("rag")));
            }
            if (param.containsKey("cards")) {
                vo.setCards(param.get("cards"));
            }
        }
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
                    .setDegraded(meta.getBool("degraded", false))
                    // ★ 检索指标也放进非流式返回：调用方（含验收脚本）不用去翻日志
                    .setRagMode(meta.getStr("ragMode"))
                    .setRagHits(meta.getInt("ragHits"))
                    .setRagCandidates(meta.getInt("ragCandidates"))
                    .setRagSearchMs(meta.getInt("ragSearchMs"))
                    .setToolCallCount(meta.getInt("toolCallCount"))
                    .setToolNames(meta.getJSONArray("toolNames") == null ? null
                            : meta.getJSONArray("toolNames").toList(String.class))
                    .setToolCalls(meta.getJSONObject("toolCalls"))
                    // ★ M6-5：记忆与打断指标。它们必须出现在**非流式**返回里 ——
                    //   验收脚本与调用方读的就是这里，不用去翻日志或查 Redis
                    .setStopped(meta.getBool("stopped", false))
                    .setMemoryHistory(meta.getInt("memoryHistory"))
                    .setCompressed(meta.getBool("compressed", false))
                    .setCompressedCount(meta.getInt("compressedCount"))
                    .setCompressMs(meta.getInt("compressMs"));
        }
        return vo;
    }

    // ==================== 停止与上下文查看（M6-5） ====================

    @Override
    public String stop(String sessionId) {
        // ⚠️ 归属校验必须在"删除标记"之前：否则任何人拿到别人的 sessionId
        //   就能把对方正在生成的回答掐掉（一个不需要登录态之外任何权限的破坏动作）
        sessionService.assertMine(sessionId);
        return generationRegistry.stop(sessionId);
    }

    @Override
    public Map<String, Object> memoryView(String sessionId) {
        sessionService.assertMine(sessionId);
        return memory.view(sessionId);
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
                IdUtil.getSnowflakeNextIdStr(), dto.getContent(),
                props.getTool().getMaxCallsPerTurn());
        turn.retrieval = new AgentContext.RetrievalOptions(
                dto.getGoal(), dto.getRagTopK(), dto.getRagThreshold(), dto.getRagHybrid());

        // ★ M6-5：在**请求线程上、调模型之前**就把"正在生成"的标记打好。
        //   这样即使 stop 请求比流被订阅还早到（用户几乎在同一瞬间点了停止），
        //   stop 也能先把它删掉 —— 流启动后的第一次检查就会发现并立即结束，
        //   而不是"白调一次真实模型再终止"（那一次调用是要花钱和耗时的）。
        generationRegistry.mark(session.getSessionId(), turn.requestId);

        // 提问与回答共用同一个 requestId —— 这是幂等兜底键的一半
        // （uk_request_role(request_id, role)），也是"停止当前这一轮"的定位依据
        sessionService.saveMessage(new ChatMessage()
                .setSessionId(session.getSessionId())
                .setUserId(userId)
                .setRequestId(turn.requestId)
                .setRole(AigcConstants.ROLE_USER)
                .setContent(dto.getContent()));
        sessionService.touch(session.getSessionId(), userId, 1, dto.getContent());
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
        // ★★ M6-5 会话记忆的三个动作，**顺序不能换**：
        //   ① 先压缩：把窗口外的旧内容折成摘要（它要用到"压缩前的"消息列表）
        //   ② 再取历史：摘要 + 最近 N 轮原文（此时列表里**还没有**当前提问）
        //   ③ 最后才把当前提问写进记忆
        //   —— ③ 如果提到 ② 前面，当前提问会既出现在历史里、又作为本轮提问出现一次，
        //      模型会以为自己被问了两次，然后可能回答两遍或答非所问。
        //      这类 bug 在 prompt 里"看起来很正常"，所以顺序必须写清楚。
        turn.compress = memory.compressIfNeeded(turn.sessionId);
        List<Message> history = memory.promptHistory(turn.sessionId);
        turn.historySize = history.size();
        memory.record(turn.sessionId, AigcConstants.ROLE_USER, turn.question);

        // ★★ M6-4：先拉一次体测档案，两个消费方共用
        //   ① RAG 的元数据过滤要用"训练目标"（减脂用户不该拿到增肌文档）；
        //   ② 模型要用档案说人话（"你体脂 15.5%、每周练 4 次"）。
        //   ⚠️ 只拉一次是刻意的：各自去 Feign 会出现"过滤器用旧档案、模型看新档案"
        //      这种极难发现的不一致。SIMPLE 策略不挂工具也不检索，直接跳过省一次调用。
        UserBodyProfileDTO profile = chatType == ChatType.SIMPLE
                ? null : bodyProfileService.load(turn.userId);
        turn.bodyProfile = profile;

        // ★ 训练目标的优先级：**请求显式指定 > 档案推导**
        //   请求级参数是给验收/演示用的（要能构造"减脂用户"这种场景）；
        //   真实用户走的是档案推导 —— 这也是"体测数据成为 AI 上下文"的兑现点。
        String goal = StrUtil.isNotBlank(turn.retrieval.goal())
                ? turn.retrieval.goal()
                : bodyProfileService.deriveGoal(profile);
        AgentContext.RetrievalOptions retrieval = new AgentContext.RetrievalOptions(
                goal, turn.retrieval.topK(), turn.retrieval.threshold(), turn.retrieval.hybrid());

        // ★ token 要在**请求线程上**取好带下去：工具会跑到 Reactor 的 boundedElastic 线程，
        //   那里 ThreadLocal 里的身份是空的（见 ToolAuthContext 的说明）
        AgentContext context = new AgentContext(
                turn.sessionId, turn.requestId, turn.userId, turn.question,
                retrieval, turn.ragTrace, turn.toolTrace, profile, TokenContext.getToken(), history);

        // ★ 调用模型**之前**先检查一次是否已被停止。
        //   用户已经明确表示不要这个回答了，那就连这次模型调用都不该发生 ——
        //   否则"点了停止还扣了一次 token"（而且它还会出现在成本统计里）。
        //   ⚠️ 这个检查只在"停止几乎与提问同时到达"时命中，正常路径不受影响。
        if (!generationRegistry.alive(turn.sessionId, turn.requestId)) {
            turn.stopped = true;
            log.info("★ 本轮在调用模型之前就已被停止，直接结束（省下一次模型调用）：requestId={}",
                    turn.requestId);
            return OrchestrationResult.of(null, Flux.<ChatResponse>empty());
        }

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

    /**
     * 检查本轮是否应该停止吐字（分布式打断的检查点）。
     *
     * <p>★ <b>带节流</b>：每个分片都查一次 Redis 的话，一段 200 字的回答
     * （分片 4 字）就是 50 次网络往返 —— 为了一个用户可能根本不会点的停止按钮，
     * 给每一次对话都加上 50 次往返。所以按时间节流（默认 200ms 检查一次），
     * 代价是停止最多有 200ms 的延迟，对"点停止"这个动作完全无感。</p>
     *
     * <p>★ 判定条件是"标记还在、且值是我的"（见 {@link GenerationRegistry#alive}）：
     * 标记消失 = 被停止或已过期；值变了 = 同会话已经开了新一轮，
     * 这一轮应当让位（这正是"同会话连发时新问题接管旧回答"的实现）。</p>
     */
    private boolean interrupted(ChatTurn turn) {
        long now = System.currentTimeMillis();
        if (now - turn.lastInterruptCheckMs < props.getStop().getCheckIntervalMs()) {
            return false;
        }
        turn.lastInterruptCheckMs = now;
        if (generationRegistry.alive(turn.sessionId, turn.requestId)) {
            return false;
        }
        turn.stopped = true;
        log.info("★ 本轮生成被停止（标记已失效）：requestId={}, 已生成 {} 字",
                turn.requestId, turn.answer.length());
        return true;
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
        // ★ 一个字都没吐出来就被停止 → 不落库。
        //   落一条空内容的助手消息会让会话列表里出现"空白气泡"，
        //   而且它会被算作一次"AI 回答过"（token 全是 0），污染使用统计。
        //   （用户提问那条照常保留 —— 他确实问过，只是没得到回答。）
        if (turn.answer.isEmpty() && turn.stopped) {
            log.info("本轮被停止且未产出内容，不落库：requestId={}", turn.requestId);
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
            // ★ 检索来源一并落库到 params（JSON 列）：
            //   ① 前端翻历史记录时，卡片要能重渲染出"参考资料"；
            //   ② 出了幻觉要能复盘 —— "当时到底给了模型哪几份依据"是第一个要确认的事，
            //      只存回答的话这件事永远查不清
            Map<String, Object> params = new LinkedHashMap<>();
            if (!turn.ragTrace.getSources().isEmpty()) {
                params.put("rag", turn.ragTrace.toMap());
            }
            if (!turn.toolTrace.getCards().isEmpty()) {
                params.put("cards", turn.toolTrace.getCards());
            }
            if (!params.isEmpty()) {
                msg.setParams(params);
            }
            // ★ 工具调用记录单独一列：它和"卡片数据"是两种东西 ——
            //   卡片是给用户看的，调用记录是给排查用的（调了什么、参数是什么、成功没有）
            if (props.getTool().isRecordInvocations() && !turn.toolTrace.isEmpty()) {
                msg.setToolCalls(turn.toolTrace.toMap());
            }
            sessionService.saveMessage(msg);
            sessionService.touch(turn.sessionId, turn.userId, 1, turn.answer.toString());
            // ★ M6-5：把这一轮的回答写进上下文窗口（Redis 那份）。
            //   落库与"进记忆"是**两件事**：① 落库（MySQL）是完整档案，一条都不会少；
            //   ② 进记忆（Redis）是"下一轮要喂给模型的窗口"，会被压缩/截断/过期。
            //   常见实现只有 ②，于是"给用户看的历史"和"给模型看的上下文"是同一份数据，
            //   两个需求互相牵制：想记得久就得留得多，留得多又让存储与列表失控。
            memory.record(turn.sessionId, AigcConstants.ROLE_ASSISTANT, turn.answer.toString());
            log.info("本轮对话完成：requestId={}, 编排={}, Agent={}, 首字 {}ms, 总耗时 {}ms, tokens={}/{}, "
                            + "检索={}(命中 {} 条/候选 {} 条, {}ms), 工具={} 次{}, 历史 {} 条{}, 降级={}",
                    turn.requestId, turn.chatType, turn.agentLabel, turn.firstTokenMs, msg.getTotalMs(),
                    turn.promptTokens, turn.completionTokens,
                    turn.ragTrace.getMode(), turn.ragTrace.getInjectedCount(),
                    turn.ragTrace.getCandidateCount(), turn.ragTrace.getSearchMs(),
                    turn.toolTrace.getCallCount(),
                    turn.toolTrace.getCards().isEmpty() ? "" : "（返回 " + turn.toolTrace.getCards().size() + " 张卡片）",
                    turn.historySize,
                    turn.compress.compressed() ? "（本轮压缩 " + turn.compress.compressedCount() + " 条）" : "",
                    degraded);
        } catch (Exception e) {
            // 落库失败不能影响用户已经看到的回答 —— 回答已经吐出去了，
            // 这里抛异常只会让前端收到一个错误，而内容其实是对的
            log.error("对话落库失败（回答已产出，不影响用户）：requestId={}", turn.requestId, e);
        }
    }

    /**
     * 收尾事件序列：<b>[引用来源 PARAM?] + STOP</b>。
     *
     * <p>★ 引用来源走 <b>PARAM 事件</b>（结构化 JSON）而不是拼进回答正文，
     * 原因是准确性：拼在正文里就得靠模型自己说"依据是《XX》"——
     * 它可能漏、可能把标题写错。而<b>引用一旦不准，比没有引用更糟</b>
     * （用户会相信一个错误的出处）。走 PARAM 的话，数据直接来自检索结果本身，
     * 不经过模型的转述；前端也能把它渲染成可展开的"参考资料"卡片。</p>
     *
     * <p>没有检索到依据时不发 PARAM（而不是发一个空数组）：
     * 前端据此可以区分"这轮没有依据"和"这轮依据为空"，
     * 而且不会有"空卡片闪一下"的体验问题。</p>
     */
    private List<ChatEventVO> tailEvents(ChatTurn turn, boolean degraded) {
        List<ChatEventVO> events = new ArrayList<>();
        // ★ 一个 PARAM 事件承载两种结构化载荷：rag（引用来源）+ cards（工具卡片）。
        //   合成一个事件而不是发两个：前端只需要处理一次"卡片区渲染"，
        //   而两个事件会让"先到的是哪个"变成一个需要约定的问题。
        Map<String, Object> payload = new LinkedHashMap<>();
        if (!turn.ragTrace.getSources().isEmpty()) {
            payload.put("rag", turn.ragTrace.toMap());
        }
        if (!turn.toolTrace.getCards().isEmpty()) {
            payload.put("cards", turn.toolTrace.getCards());
        }
        if (!payload.isEmpty()) {
            events.add(ChatEventVO.param(JSONUtil.toJsonStr(payload)).setRequestId(turn.requestId));
        }
        events.add(stopEvent(turn, degraded));
        return events;
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
                        // ★ 检索可观测：命中/候选/耗时。改检索参数之后，
                        //   "命中数变了没有"是最直接的反馈（比盯着回答猜靠谱得多）
                        .set("ragMode", turn.ragTrace.getMode())
                        .set("ragHits", turn.ragTrace.getInjectedCount())
                        .set("ragCandidates", turn.ragTrace.getCandidateCount())
                        .set("ragSearchMs", turn.ragTrace.getSearchMs())
                        .set("ragContextChars", turn.ragTrace.getContextChars())
                        // ★ 工具可观测：调了几次、调了哪些。
                        //   "AI 为什么答得慢/答得贵"经常就是工具被反复调
                        .set("toolCallCount", turn.toolTrace.getCallCount())
                        .set("toolNames", turn.toolTrace.toMap().get("toolNames"))
                        // ★ 完整调用明细（名称/参数/结果摘要/耗时）：
                        //   排查"AI 为什么答非所问"时第一件要看的就是它
                        .set("toolCalls", turn.toolTrace.isEmpty() ? null : turn.toolTrace.toMap())
                        // ★ M6-5 记忆与打断的可观测：
                        //   · stopped        —— 本轮是不是被停止的（前端据此不再提示"回答可能不完整"）
                        //   · memoryHistory  —— 这轮带上了几条历史（多轮到底有没有生效，一眼可见）
                        //   · compressed     —— 这轮是否触发了上下文压缩（它会额外花一次模型调用）
                        .set("stopped", turn.stopped)
                        .set("memoryHistory", turn.historySize)
                        .set("compressed", turn.compress.compressed())
                        .set("compressedCount", turn.compress.compressedCount())
                        .set("compressMs", (int) turn.compress.costMs())
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
        /** ★ 本轮检索轨迹：由 RAG Advisor 就地填充，收尾时用来发 PARAM、落库、记指标 */
        private final RagTrace ragTrace = new RagTrace();
        private int firstTokenMs = -1;
        private Integer promptTokens;
        private Integer completionTokens;
        private volatile boolean degraded;
        /**
         * 检索参数覆盖（目标 / topK / 阈值），来自请求参数。
         * ★ M6-4 起"目标"会改由 BodyTools 从体测档案提供；保留请求参数是为了让
         *   验收与演示能精确构造"减脂用户 / 增肌用户"和不同 topK、不同阈值的场景。
         */
        private AgentContext.RetrievalOptions retrieval = AgentContext.RetrievalOptions.none();
        /** ★ 本轮工具调用轨迹（工具就地填充），收尾时发 PARAM、落 tool_calls */
        private final ToolTrace toolTrace;
        /** ★ 本轮拉取的体测档案（编排层拉、两个消费方共用） */
        private UserBodyProfileDTO bodyProfile;

        // ==================== M6-5 记忆与打断 ====================

        /** 本轮是否被停止（用户点停止 / 被新一轮接管 / 标记过期） */
        private volatile boolean stopped;
        /** 上次检查打断标记的时间戳（节流，见 {@code interrupted}） */
        private long lastInterruptCheckMs;
        /** 本轮带上的历史消息条数（含摘要那条）—— 多轮是否真的生效，看它 */
        private int historySize;
        /** 本轮的压缩结果（绝大多数是 {@code none()}） */
        private CompressOutcome compress = CompressOutcome.none();
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

        private ChatTurn(String sessionId, Long userId, String requestId, String question, int maxToolCalls) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.requestId = requestId;
            this.question = question;
            this.toolTrace = new ToolTrace(maxToolCalls);
        }
    }
}
