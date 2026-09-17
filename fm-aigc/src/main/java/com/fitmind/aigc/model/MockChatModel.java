package com.fitmind.aigc.model;

import com.fitmind.aigc.config.AigcProperties;
import com.fitmind.aigc.constants.AigcConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Mock 模型通道 —— 让 AI 链路可以被自动化验收。
 *
 * <p><b>为什么需要它</b>：AI 链路依赖外部大模型，而外部大模型不可控
 * （限流、抖动、要花钱、需要 key）。这会让传统的验收方式失效 ——
 * 你没法在脚本里断言"模型会说什么"。</p>
 *
 * <p><b>它怎么解决这个问题</b>：实现 Spring AI 的 {@link ChatModel} 接口本身，
 * 于是上层代码用的还是标准的 {@code ChatClient}，**完全不知道自己在跟 Mock 说话**。
 * 这一点很关键 —— 如果 Mock 只是"绕过模型直接返回一段假数据"，
 * 那它验证不了 prompt 组装、Advisor 链、SSE 拼接、工具循环这些真正容易出错的地方。</p>
 *
 * <p><b>行为要足够真实（6 条，缺一条就测不出对应的问题）</b>：
 * <ol>
 *   <li><b>按输入关键词返回可预期的回答</b> —— 断言才有依据</li>
 *   <li><b>真的分片流式输出</b> —— 否则 SSE 拼接、打字机、
 *       打断时"已生成部分落库"这些代码在验收里等于没跑</li>
 *   <li><b>能识别"分类任务"并返回确定的类别</b> —— 否则多 Agent 路由永远走兜底</li>
 *   <li><b>能识别"检索为空"并按拒答回答</b> —— 否则 RAG 的拒答链路是空的</li>
 *   <li>★ <b>能发起工具调用、并在拿到工具结果后生成"基于真实数据"的回答</b> ——
 *       否则 Tool Calling 链路在验收里等于没跑（而它恰恰是全链路里最长的一条）</li>
 *   <li><b>能模拟超时 / 报错</b>，并<b>记录被调用的 prompt 全文</b> ——
 *       前者验证降级，后者用于断言"检索到的文档确实进了 prompt"</li>
 * </ol>
 * </p>
 *
 * <p>★ 这与 M4-2 的 <b>Mock 支付渠道</b>、M4-4 的 <b>Mock 退款</b>是同一个思想：
 * <b>把"外部依赖的真实性"和"我方链路的正确性"分开验证。</b>
 * 本项目已经用了三次 —— 面试时可以当成一个方法论来讲。</p>
 */
@Slf4j
public class MockChatModel implements ChatModel {

    // ==================== 指令标记 ====================
    // 用法：在提问里带上标记，Mock 就切换行为。验收脚本靠它精确控制被测路径。

    /** 模拟模型侧超时（抛 TimeoutException，与 Reactor timeout 同类型） */
    public static final String CMD_TIMEOUT = "[MOCK:TIMEOUT]";
    /** 模拟模型服务报错 */
    public static final String CMD_ERROR = "[MOCK:ERROR]";
    /** 强制发起一次工具调用（不依赖关键词判定，用于精确控制） */
    public static final String CMD_TOOL = "[MOCK:TOOL]";
    /** 放慢分片速度（测打断：慢到用户有机会点"停止"） */
    public static final String CMD_SLOW = "[MOCK:SLOW]";

    /**
     * ★ 检索为空时 Mock 的"拒答"回答。
     *
     * <p>为什么 Mock 需要认识这件事：RAG 的"拒答"能力最终要靠模型说出口，
     * 而 Mock 是确定性替身 —— 它必须<b>按 prompt 的形态</b>决定说什么，
     * 否则"知识库里没有相关内容 → 应该说不知道"这条链路在验收里就是空的
     * （Mock 永远会返回一段像样的健身内容，看起来一切正常）。</p>
     *
     * <p>判定依据是 prompt 里有没有"没有检索到"这段说明 ——
     * 也就是 <b>Mock 在验证"我们的 prompt 组装对不对"，而不是在验证自己</b>。</p>
     */
    public static final String REFUSAL_ANSWER =
            "这个问题不在我的知识库里，我不能凭空给你编一个答案。"
                    + "如果你愿意，可以换个更具体的训练或营养问题问我。";

    /**
     * ★ 知识库不可用时的降级回答标记。
     *
     * <p>Mock 必须把"降级"演得和"拒答"明显不同 —— 否则验收里这两条路径
     * 长得一模一样，"区分它们"这件事就没有被验证到。</p>
     */
    public static final String DEGRADED_KB_MARK = "（知识库这次没能查，以上是通用建议）";

    /** Mock 认识的全部工具名（兜底判定用，见 {@link #availableTools}） */
    private static final List<String> KNOWN_TOOLS = List.of(
            "queryMyBodyProfile", "searchCourses", "queryCourseDetail", "prePlaceOrder");

    /** 搜课关键词的候选词表（从提问里挑一个最像关键词的） */
    private static final List<String> COURSE_KEYWORDS = List.of(
            "哑铃", "增肌", "减脂", "核心", "入门", "拉伸", "全身", "上肢", "下肢",
            "有氧", "力量", "新手", "塑形", "居家");

    /** 最终回答里回显工具结果的最大字符数（太长会把回答撑爆） */
    private static final int MAX_TOOL_ECHO = 400;

    /** Mock 摘要里最多回显几条历史片段 */
    private static final int SUMMARIZE_FRAGMENTS = 3;
    /** 每条片段截断长度（够验收断言"早期内容确实在摘要里"即可） */
    private static final int SUMMARIZE_FRAGMENT_LEN = 16;

    private final AigcProperties props;
    private final StringRedisTemplate redis;

    public MockChatModel(AigcProperties props, StringRedisTemplate redis) {
        this.props = props;
        this.redis = redis;
    }

    // ==================== ChatModel ====================

    @Override
    public ChatResponse call(Prompt prompt) {
        MockPlan plan = plan(prompt);
        if (plan.error() != null) {
            // 同步路径不能直接 throw 受检异常 → 包一层并保留 cause。
            // 注意降级处理是靠 onErrorResume（不挑异常类型），所以包装不影响
            // "模型侧超时"和"我方超时"走同一个降级分支这件事。
            throw new IllegalStateException("Mock 通道按指令返回错误", plan.error());
        }
        return toResponse(plan.answer(),
                estimateTokens(promptChars(prompt)),
                estimateTokens(plan.answer().length()),
                plan.toolCalls());
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        MockPlan plan = plan(prompt);
        if (plan.error() != null) {
            return Flux.error(plan.error());
        }
        int promptTokens = estimateTokens(promptChars(prompt));

        // ★ 工具调用不分片：模型"决定调工具"不是打字过程，切成多片只会让上层
        //   收到好几份一样的 tool_call（工具会被执行多次）。
        //   真模型也是这个行为 —— 工具调用的 delta 是整体到达的。
        if (!plan.toolCalls().isEmpty()) {
            return Flux.just(toResponse("", promptTokens, 1, plan.toolCalls()));
        }

        List<String> chunks = split(plan.answer(), props.getMock().getChunkSize());
        // ★ 每个分片都带上"到当前为止"的累计 token，而不是只在最后一片给。
        //   真模型也是这个行为（有些渠道每片都回 usage），而且这样调用方
        //   **不需要写"最后一片才取 usage"的特判** —— 少一个容易写错的约定。
        StringBuilder accumulated = new StringBuilder();
        return Flux.fromIterable(chunks)
                .delayElements(Duration.ofMillis(plan.delayMs()))
                .map(chunk -> {
                    accumulated.append(chunk);
                    return toResponse(chunk, promptTokens, estimateTokens(accumulated.length()), List.of());
                });
    }

    // ==================== 内部实现 ====================

    /**
     * 决定这一轮怎么"演"。
     *
     * <p>顺序有讲究：
     * ① 错误指令最优先（否则带 {@code [MOCK:ERROR]} 的提问会先被关键词表命中、
     * 返回一段正常回答，降级路径根本走不到）；
     * ② <b>工具结果优先于一切正常生成</b> —— 一旦 prompt 里出现了
     * {@code ToolResponseMessage}，说明工具已经跑完了，这时必须产出最终回答，
     * 否则会陷入"再调一次工具"的循环；
     * ③ 分类任务在工具之后（路由 Agent 不挂工具）。</p>
     */
    private MockPlan plan(Prompt prompt) {
        String userText = lastUserText(prompt);
        snapshotPrompt(prompt);

        if (userText.contains(CMD_TIMEOUT)) {
            log.info("[Mock通道] 按指令模拟模型超时");
            // ★ 用 TimeoutException 而不是别的类型：Reactor 的 timeout() 抛的也是它，
            //   于是"模型侧超时"与"我方超时"共用同一个降级分支（一处兜底，两条路都覆盖）
            return new MockPlan("", new TimeoutException("模拟模型侧超时"), 0, List.of());
        }
        if (userText.contains(CMD_ERROR)) {
            log.info("[Mock通道] 按指令模拟模型服务异常");
            return new MockPlan("", new RuntimeException("模拟模型服务异常"), 0, List.of());
        }

        // ★ M6-5：摘要任务（上下文压缩）—— 必须排在"按关键词生成回答"之前。
        //
        //   为什么：压缩请求的 user 消息里是"整段待压缩的对话"，
        //   它必然包含大量健身关键词（推荐、蛋白质、减脂……）。
        //   如果不先拦下来，Mock 会把压缩请求当成一次普通提问，
        //   返回一段健身回答 —— 于是"摘要"变成了一段奇怪的话塞进摘要键里，
        //   而链路看起来一切正常（这就是"Mock 没覆盖到 = 这条链路在验收里没跑"）。
        if (isSummarizeTask(prompt)) {
            String summary = summarize(prompt);
            log.info("[Mock通道] 识别为对话摘要任务 → 返回确定性摘要（{} 字）", summary.length());
            return new MockPlan(summary, null, props.getMock().getChunkDelayMs(), List.of());
        }

        // ★ M6-4：工具结果已就位 —— 先判断"还差工具吗"，差就继续调，不差才收尾。
        //
        //   ⚠️ "拿到工具结果就立刻收尾"是不够真实的：真模型会分步来
        //   （先查用户档案 → 再按目标搜课 → 最后组织回答）。
        //   多步链也让**工具循环本身**被真正跑满 —— 而循环恰恰是最容易出问题的地方
        //   （无限循环、结果丢失、请求体越来越长）。
        String toolResults = collectToolResults(prompt);
        if (toolResults != null) {
            java.util.Set<String> called = calledToolNames(prompt);
            MockPlan next = planToolCall(prompt, userText, called);
            if (next != null) {
                log.info("[Mock通道] 已有工具结果（{} 字），仍需继续调用工具，已调用过={}", toolResults.length(), called);
                return next;
            }
            log.info("[Mock通道] 工具齐了（已调用 {}）→ 生成最终回答", called);
            return new MockPlan(finalAnswer(toolResults), null, props.getMock().getChunkDelayMs(), List.of());
        }

        // ★ 分类任务（意图路由）：必须返回一个确定的类别
        if (isClassifyTask(prompt)) {
            String category = classify(userText);
            log.info("[Mock通道] 识别为分类任务，按关键词判定类别={}", category);
            // 分类任务返回"干净的一个词"（不带上下文后缀）——
            // 真模型在这种 prompt 下也是只输出一个词的
            return new MockPlan(category, null, 0, List.of());
        }

        // ★ M6-6：知识库**不可用**（不是"没有内容"）→ 演"降级但仍回答"。
        //   必须排在拒答判定之前，且判定用的关键字要与拒答文案严格不同 ——
        //   两条路径的差异只有一句话，靠模糊匹配是测不出区分度的。
        if (containsAny(userText, "没能检索知识库")) {
            log.info("[Mock通道] prompt 里带「知识库不可用」说明 → 按降级回答（照常答 + 说明没查证）");
            return new MockPlan(answer(userText, prompt) + DEGRADED_KB_MARK,
                    null, props.getMock().getChunkDelayMs(), List.of());
        }

        // ★ RAG 拒答：知识库检索为空时，prompt 里会带一段"没有检索到资料"的说明。
        //   一个行为端正的模型此时应该拒答而不是硬编 —— Mock 照着做，
        //   于是"检索为空 → 拒答"这条链路在验收里**真的被执行到**。
        if (containsAny(userText, "没有检索到任何相关的知识库资料")) {
            log.info("[Mock通道] prompt 里带「无资料」说明 → 按拒答回答（模拟行为端正的模型）");
            return new MockPlan(REFUSAL_ANSWER, null, props.getMock().getChunkDelayMs(), List.of());
        }

        // ★ M6-4：prompt 里带了工具、且提问有明确诉求 → 发起一次工具调用
        MockPlan toolPlan = planToolCall(prompt, userText, java.util.Set.of());
        if (toolPlan != null) {
            return toolPlan;
        }

        int delay = userText.contains(CMD_SLOW)
                ? props.getMock().getChunkDelayMs() * 10
                : props.getMock().getChunkDelayMs();
        return new MockPlan(answer(userText, prompt), null, delay, List.of());
    }

    // ==================== M6-4：工具调用模拟 ====================

    /**
     * 决定要不要调工具、调哪个、带什么参数。
     *
     * <p>★ 判定依据是<b>提问里的关键词</b>，不是"提问里带了测试标记" ——
     * 这一点是刻意的：带了标记的 Mock 只能验证"我们写的代码能跑通标记"，
     * 而按关键词判定至少复现了真模型的决策形态
     * （用户说"推荐一门哑铃课" → 模型决定去搜课）。
     * 验收脚本也用自然语言提问来驱动它，而不是塞一个只有 Mock 认识的暗号。</p>
     *
     * @return null 表示这一轮不调工具（直接生成回答）
     */
    private MockPlan planToolCall(Prompt prompt, String userText, java.util.Set<String> alreadyCalled) {
        List<String> tools = availableTools(prompt);
        if (tools.isEmpty()) {
            return null;
        }
        String q = stripCommands(userText);
        boolean forced = userText.contains(CMD_TOOL);

        // ① 用户档案（★ 注意：没有 userId 参数 —— 工具查的永远是"当前登录用户"）
        if (tools.contains("queryMyBodyProfile") && !alreadyCalled.contains("queryMyBodyProfile")
                && (forced || containsAny(q, "体测", "身体档案", "我的档案", "体脂", "BMI", "bmi", "我的身体"))) {
            return toolCall("queryMyBodyProfile", "{}");
        }
        // ② 下单（courseIds 从提问里抽数字；抽不到就传空，正好验证"参数校验"那条兜底）
        if (tools.contains("prePlaceOrder") && !alreadyCalled.contains("prePlaceOrder")
                && containsAny(q, "买", "下单", "报名", "支付", "购买", "付款")) {
            return toolCall("prePlaceOrder", "{\"courseIds\":" + extractIds(q) + "}");
        }
        // ③ 课程详情（问题里带课程 id 时）
        if (tools.contains("queryCourseDetail") && !alreadyCalled.contains("queryCourseDetail")
                && containsAny(q, "详情", "介绍", "几节", "目录")) {
            List<String> ids = parseIds(q);
            if (!ids.isEmpty()) {
                return toolCall("queryCourseDetail", "{\"courseId\":" + ids.get(0) + "}");
            }
        }
        // ④ 搜课
        if (tools.contains("searchCourses") && !alreadyCalled.contains("searchCourses")
                && (forced || containsAny(q, "推荐", "课程", "上课", "练什么", "适合我", "搜"))) {
            return toolCall("searchCourses", "{\"keyword\":\"" + extractKeyword(q) + "\"}");
        }
        return null;
    }

    /** prompt 里已经返回过结果的工具名（多步链靠它避免重复调用同一个工具） */
    private java.util.Set<String> calledToolNames(Prompt prompt) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (Message m : prompt.getInstructions()) {
            if (m instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                    names.add(r.name());
                }
            }
        }
        return names;
    }

    /**
     * 当前 prompt 里有哪些工具可用。
     *
     * <p>① 优先读模型选项里的工具定义（{@code ChatClient.tools(...)} 会把它们放进
     * {@code ToolCallingChatOptions}）；② 读不到就退回到"system 提示词里点名了工具名"
     * —— 我们的人设提示词本来就写清了有哪些工具，所以这条兜底是可用的。
     * 两条都不命中就说明这一轮确实没挂工具（例如 SIMPLE 策略），直接生成回答。</p>
     */
    private List<String> availableTools(Prompt prompt) {
        List<String> names = new ArrayList<>();
        try {
            if (prompt.getOptions() instanceof ToolCallingChatOptions options
                    && options.getToolCallbacks() != null) {
                options.getToolCallbacks().forEach(cb -> names.add(cb.getToolDefinition().name()));
            }
        } catch (Exception e) {
            log.debug("[Mock通道] 读取工具定义失败，改用提示词兜底：{}", e.getMessage());
        }
        if (names.isEmpty()) {
            String all = prompt.getContents();
            for (String candidate : KNOWN_TOOLS) {
                if (all.contains(candidate)) {
                    names.add(candidate);
                }
            }
        }
        return names;
    }

    /** 组装一次工具调用。id 由"工具名+参数"派生，是确定性的 —— 验收断言才有依据 */
    private MockPlan toolCall(String name, String args) {
        String id = "call_" + Integer.toHexString((name + args).hashCode());
        log.info("[Mock通道] 决定调用工具：{}（toolCallId={}）", name, id);
        return new MockPlan("", null, 0,
                List.of(new AssistantMessage.ToolCall(id, "function", name, args)));
    }

    /**
     * 收集 prompt 里的工具结果。
     *
     * <p>★ 这个方法就是 Mock 的"循环终止条件"：一旦拿到工具结果，
     * 就必须产出最终回答，而不是再调一次工具。</p>
     *
     * @return null 表示还没有工具结果（这是第一轮）
     */
    private String collectToolResults(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        for (Message m : prompt.getInstructions()) {
            if (m instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                    if (r.responseData() != null && !r.responseData().isBlank()) {
                        sb.append(r.responseData()).append('\n');
                    }
                }
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * 基于工具结果的最终回答。
     *
     * <p>★ <b>刻意把工具返回的原文回显进回答里</b>：这是"工具确实被执行了、
     * 结果确实到达了模型"的直接证据。如果只输出一句"我查到了一些课程"，
     * 验收脚本就只能断言"回答非空"，那等于没断言。</p>
     */
    private String finalAnswer(String toolResults) {
        String echo = toolResults.trim();
        if (echo.length() > MAX_TOOL_ECHO) {
            echo = echo.substring(0, MAX_TOOL_ECHO) + "…";
        }
        return "我查了一下，结果如下：\n" + echo + "\n（Mock 通道 · 基于工具返回的真实数据）";
    }

    /** 从提问里抽数字当课程 id（真模型也是从用户话里读 id 的） */
    private String extractIds(String text) {
        return "[" + String.join(",", parseIds(text)) + "]";
    }

    private List<String> parseIds(String text) {
        List<String> ids = new ArrayList<>();
        for (String token : text.split("[^0-9]+")) {
            if (token.length() >= 3) {
                ids.add(token);
            }
        }
        return ids;
    }

    /** 从提问里挑一个搜课关键词：先查词表，再退化到"去掉虚词后的前几个字" */
    private String extractKeyword(String q) {
        for (String kw : COURSE_KEYWORDS) {
            if (q.contains(kw)) {
                return kw;
            }
        }
        String cleaned = q.replaceAll("[？?。，,！!的了想帮我推荐一门个课程有些什么]", "").trim();
        if (cleaned.isEmpty()) {
            cleaned = q;
        }
        return cleaned.length() > 8 ? cleaned.substring(0, 8) : cleaned;
    }

    // ==================== M6-5：上下文摘要模拟 ====================

    /**
     * 这一次调用是不是"压缩历史对话"（而不是在回答用户）。
     *
     * <p>判定依据是 <b>system 提示词的语义特征</b>（{@code AigcConstants.COMPRESS_SYSTEM_MARK}），
     * 不是"prompt 里带了某个测试标记" —— 与路由识别、拒答识别同一套做法：
     * 提示词改了措辞只要还保留身份描述就不会失效，生产提示词里也不需要塞测试专用的东西。</p>
     */
    private boolean isSummarizeTask(Prompt prompt) {
        for (Message m : prompt.getInstructions()) {
            if (m instanceof SystemMessage sm) {
                String text = sm.getText();
                if (text != null && text.contains(AigcConstants.COMPRESS_SYSTEM_MARK)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 生成确定性摘要。
     *
     * <p>★ 摘要里<b>刻意带上被压缩对话的前几条片段</b>：这是"早期内容真的被搬进了摘要"
     * 的直接证据。验收可以据此断言一件很有说服力的事 ——
     * <b>聊到第 20 轮时，prompt 里仍然能找到第 1 轮说过的内容</b>
     * （它不在最近 N 轮的原文里，只可能在摘要里）。</p>
     */
    private String summarize(Prompt prompt) {
        String text = lastUserText(prompt);
        int idx = text.indexOf(AigcConstants.COMPRESS_TRANSCRIPT_HEADER);
        String body = idx >= 0 ? text.substring(idx + AigcConstants.COMPRESS_TRANSCRIPT_HEADER.length()) : text;

        List<String> fragments = new ArrayList<>();
        int lines = 0;
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            lines++;
            if (fragments.size() < SUMMARIZE_FRAGMENTS) {
                // 去掉 transcript 里的 "user: " / "assistant: " 前缀，
                // 让摘要读起来像"人写的"而不是"回显的原文"（真模型也会这么做）
                String clean = t.replaceFirst("^(user|assistant):\\s*", "");
                fragments.add(clean.length() > SUMMARIZE_FRAGMENT_LEN
                        ? clean.substring(0, SUMMARIZE_FRAGMENT_LEN) : clean);
            }
        }
        return AigcConstants.COMPRESS_RESULT_PREFIX + " 之前聊过：" + String.join(" / ", fragments)
                + "。（Mock 通道 · 由 " + lines + " 条历史压缩而来）";
    }

    // ==================== 通用判定与生成 ====================

    /**
     * 判断这是不是一个"分类任务"（如意图路由）。
     *
     * <p>★ 为什么 Mock 需要识别任务类型：多 Agent 路由的验收要能
     * <b>断言"这句话被分派给了正确的 Agent"</b>。如果 Mock 对所有提问都返回
     * 同一段话，路由解析就会永远失败、永远走兜底分支 ——
     * 表现是"路由功能看起来没生效"，而实际上是 Mock 通道没能覆盖到这条链路。</p>
     *
     * <p>识别方式是<b>判断 system 提示词的语义特征</b>（"分类器"/"只输出一个单词"），
     * 而不是匹配某个具体文件名或标记 —— 这样调整提示词措辞不会让它失效，
     * 生产用的提示词里也不需要塞任何测试专用的标记。</p>
     */
    private boolean isClassifyTask(Prompt prompt) {
        for (Message m : prompt.getInstructions()) {
            if (m instanceof SystemMessage sm) {
                String text = sm.getText();
                if (text != null && (text.contains("分类器") || text.contains("只输出一个单词")
                        || text.contains("意图分类"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 确定性的"意图分类"：按提问里的关键词判定类别。
     *
     * <p>规则与真实提示词里的类别定义一一对应，所以验收脚本可以用
     * "帮我推荐一门课"、"这门课多少钱" 这类提问精确控制路由结果。</p>
     */
    private String classify(String userText) {
        String q = stripCommands(userText);
        if (q.contains("推荐") || q.contains("适合我") || q.contains("该练什么") || q.contains("帮我挑")) {
            return "RECOMMEND";
        }
        if (q.contains("买") || q.contains("价格") || q.contains("多少钱") || q.contains("报名")
                || q.contains("支付") || q.contains("退款")) {
            return "BUY";
        }
        if (q.contains("课") || q.contains("课程")) {
            return "CONSULT";
        }
        return "KNOWLEDGE";
    }

    /**
     * 生成可预期的回答。
     *
     * <p>★ 回答末尾固定带上 {@code （Mock 通道 · 上下文 N 字 · 资料 M 条）} ——
     * 这是<b>验收 RAG 的两个抓手</b>：
     * ① N 是"送进模型的 prompt 总长度"：脚本先断言"不带知识库时 N 很小"，
     *    再断言"接入检索后 N 明显变大"，能证明检索到的文档真的进了 prompt，
     *    而不是"只被检索出来就丢了"；
     * ② M 是"资料条数"：从 prompt 里数出来的，证明资料块被正确拼装。
     * （只看模型输出的措辞是证明不了这两件事的 —— 模型自己本来就可能知道这些知识。）</p>
     */
    private String answer(String userText, Prompt prompt) {
        String q = stripCommands(userText);
        String core;
        if (q.contains("蛋白质") || q.contains("增肌")) {
            core = "增肌期建议每公斤体重摄入 1.6~2.2 克蛋白质，分 3~4 餐均匀摄入效果更好。"
                    + "你现在 64 公斤，也就是每天大约 100~140 克，优先从鸡蛋、鸡胸、牛奶、豆制品里拿。";
        } else if (q.contains("减脂") || q.contains("减肥") || q.contains("瘦")) {
            core = "减脂的核心是热量缺口，一般建议每天缺口 300~500 千卡，"
                    + "配合每周 3 次以上力量训练保住肌肉。不建议缺口超过 800，那样掉的多半是肌肉。";
        } else if (q.contains("课程") || q.contains("推荐")) {
            core = "按你的情况，我会优先推荐从基础动作模式开始，先把深蹲、推、拉这三类动作做标准。";
        } else if (q.isBlank()) {
            core = "我在，说说你今天想练什么？";
        } else {
            core = "关于「" + q + "」：这是个好问题。";
        }
        int docs = countRetrievedDocs(prompt);
        String suffix = docs > 0
                ? "（Mock 通道 · 上下文 " + promptChars(prompt) + " 字 · 资料 " + docs + " 条）"
                : "（Mock 通道 · 上下文 " + promptChars(prompt) + " 字）";
        return core + suffix;
    }

    /** 数 prompt 里有几个"参考资料"条目（行为等价于"模型看到了几份依据"） */
    private int countRetrievedDocs(Prompt prompt) {
        String all = prompt.getContents();
        int count = 0;
        int idx = all.indexOf(AigcConstants.RAG_CONTEXT_HEADER);
        if (idx < 0) {
            return 0;
        }
        int footer = all.indexOf(AigcConstants.RAG_CONTEXT_FOOTER, idx);
        String block = footer > 0 ? all.substring(idx, footer) : all.substring(idx);
        for (String line : block.split("\n")) {
            if (line.trim().startsWith("[")) {
                count++;
            }
        }
        return count;
    }

    private boolean containsAny(String text, String... keys) {
        if (text == null) {
            return false;
        }
        for (String key : keys) {
            if (text.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /** 把回答切成"像真模型一样"的分片 */
    private List<String> split(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return List.of("");
        }
        int size = Math.max(1, chunkSize);
        for (int i = 0; i < text.length(); i += size) {
            chunks.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return chunks;
    }

    private ChatResponse toResponse(String text, int promptTokens, int completionTokens,
                                    List<AssistantMessage.ToolCall> toolCalls) {
        // ★ 带 tool_call 的响应**正文是空的** —— 真模型调工具时也是这样：
        //   它不是"边说边调"，而是先说要调哪个工具（结构化），拿到结果再组织语言。
        AssistantMessage message = AssistantMessage.builder()
                .content(text)
                .toolCalls(toolCalls)
                .build();
        Generation generation = new Generation(message, ChatGenerationMetadata.NULL);
        // ★ token 数不能为 0：可观测那组验收断言的就是"这些列非 0"。
        //   这里按"中文约 2 字符 1 token"粗估 —— Mock 不需要精确，
        //   但必须是"像真的"，否则断言形同虚设。
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model("mock-chat")
                .keyValue("provider", AigcConstants.PROVIDER_MOCK)
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build();
        return new ChatResponse(List.of(generation), metadata);
    }

    private int estimateTokens(int chars) {
        return Math.max(1, chars / 2);
    }

    private String lastUserText(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage um) {
                return um.getText();
            }
        }
        return "";
    }

    /** prompt 总字符数（system + 历史 + 用户消息 + 检索到的文档，全都算） */
    private int promptChars(Prompt prompt) {
        int total = 0;
        for (Message m : prompt.getInstructions()) {
            String t = m.getText();
            if (t != null) {
                total += t.length();
            }
        }
        return total;
    }

    private String stripCommands(String text) {
        return text.replace(CMD_TIMEOUT, "").replace(CMD_ERROR, "")
                .replace(CMD_TOOL, "").replace(CMD_SLOW, "").trim();
    }

    /**
     * 把 prompt 全文存 Redis，供验收脚本核对。
     *
     * <p>★ 为什么要存这个：M6-3 的 RAG 验收里最核心的一条断言是
     * "检索到的文档确实进了 prompt"。模型输出是**间接证据**
     * （回答得像样也可能只是模型自己会的），prompt 全文才是直接证据。</p>
     *
     * <p>存 Redis 而不是内存 Map：内存 Map 单实例够用，但验收脚本要读它
     * 就得专门暴露一个接口，而那个接口又要处理鉴权（脚本走网关会 401）。
     * 存 Redis 后脚本直接读库 —— <b>测试用的旁路通道不该占用业务接口</b>。</p>
     *
     * <p>写入失败只记日志不抛：这是测试设施，不能因为它把业务链路搞挂。</p>
     */
    private void snapshotPrompt(Prompt prompt) {
        if (!props.getMock().isSnapshotPrompt() || redis == null) {
            return;
        }
        try {
            String key = AigcConstants.MOCK_PROMPT_SNAPSHOT_KEY;
            redis.opsForList().leftPush(key, prompt.getContents());
            redis.opsForList().trim(key, 0, AigcConstants.MOCK_PROMPT_SNAPSHOT_LIMIT - 1);
        } catch (Exception e) {
            log.warn("[Mock通道] prompt 快照写入失败（不影响对话）：{}", e.getMessage());
        }
    }

    /** 一轮"演出计划" */
    private record MockPlan(String answer, Throwable error, int delayMs,
                            List<AssistantMessage.ToolCall> toolCalls) {
    }
}
