package com.fitmind.aigc.model;

import com.fitmind.aigc.config.AigcProperties;
import com.fitmind.aigc.constants.AigcConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
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
 * 那它验证不了 prompt 组装、Advisor 链、SSE 拼接这些真正容易出错的地方。</p>
 *
 * <p><b>行为要足够真实（5 条，缺一条就测不出对应的问题）</b>：
 * <ol>
 *   <li><b>按输入关键词返回可预期的回答</b> —— 断言才有依据</li>
 *   <li><b>真的分片流式输出</b> —— 否则 SSE 拼接、打字机、
 *       打断时"已生成部分落库"这些代码在验收里等于没跑</li>
 *   <li><b>能模拟工具调用</b>（M6-4）—— 验证 Tool Calling 链路</li>
 *   <li><b>能模拟超时 / 报错</b> —— 验证降级路径。★ 超时抛的是
 *       {@link TimeoutException}，与 Reactor 的 {@code timeout()} 抛的类型<b>相同</b>，
 *       所以"模型侧超时"和"我方超时"会走进同一个降级分支</li>
 *   <li><b>记录被调用的 prompt 全文</b> —— 用于断言"RAG 检索到的文档确实进了 prompt"。
 *       只看模型输出是验证不了这件事的</li>
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
    /** 模拟工具调用（M6-4 用：先返回一次 tool_call，再由上层带着工具结果二次请求） */
    public static final String CMD_TOOL = "[MOCK:TOOL]";
    /** 放慢分片速度（测打断：慢到用户有机会点"停止"） */
    public static final String CMD_SLOW = "[MOCK:SLOW]";

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
                estimateTokens(plan.answer().length()));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        MockPlan plan = plan(prompt);
        if (plan.error() != null) {
            return Flux.error(plan.error());
        }
        List<String> chunks = split(plan.answer(), props.getMock().getChunkSize());
        int promptTokens = estimateTokens(promptChars(prompt));
        // ★ 每个分片都带上"到当前为止"的累计 token，而不是只在最后一片给。
        //   真模型也是这个行为（有些渠道每片都回 usage），而且这样调用方
        //   **不需要写"最后一片才取 usage"的特判** —— 少一个容易写错的约定。
        StringBuilder accumulated = new StringBuilder();
        return Flux.fromIterable(chunks)
                .delayElements(Duration.ofMillis(plan.delayMs()))
                .map(chunk -> {
                    accumulated.append(chunk);
                    return toResponse(chunk, promptTokens, estimateTokens(accumulated.length()));
                });
    }

    // ==================== 内部实现 ====================

    /**
     * 决定这一轮怎么"演"。
     *
     * <p>顺序有讲究：错误指令优先于正常回答，否则带 {@code [MOCK:ERROR]}
     * 的提问会先被关键词表命中、返回一段正常回答，降级路径根本走不到。</p>
     */
    private MockPlan plan(Prompt prompt) {
        String userText = lastUserText(prompt);
        snapshotPrompt(prompt);

        if (userText.contains(CMD_TIMEOUT)) {
            log.info("[Mock通道] 按指令模拟模型超时");
            // ★ 用 TimeoutException 而不是别的类型：Reactor 的 timeout() 抛的也是它，
            //   于是"模型侧超时"与"我方超时"共用同一个降级分支（一处兜底，两条路都覆盖）
            return new MockPlan("", new TimeoutException("模拟模型侧超时"), 0);
        }
        if (userText.contains(CMD_ERROR)) {
            log.info("[Mock通道] 按指令模拟模型服务异常");
            return new MockPlan("", new RuntimeException("模拟模型服务异常"), 0);
        }
        if (userText.contains(CMD_TOOL)) {
            // 工具调用由上层处理（见 ToolCallingManager 的接入，M6-4）；
            // 这里先把"一个只带 tool_call、不带正文"的响应形态准备好
            log.info("[Mock通道] 按指令模拟工具调用");
            return new MockPlan(buildToolAnswer(userText), null, props.getMock().getChunkDelayMs());
        }

        // ★ 分类任务（意图路由）：必须返回一个确定的类别
        if (isClassifyTask(prompt)) {
            String category = classify(userText);
            log.info("[Mock通道] 识别为分类任务，按关键词判定类别={}", category);
            // 分类任务返回"干净的一个词"（不带上下文后缀）——
            // 真模型在这种 prompt 下也是只输出一个词的
            return new MockPlan(category, null, 0);
        }

        int delay = userText.contains(CMD_SLOW)
                ? props.getMock().getChunkDelayMs() * 10
                : props.getMock().getChunkDelayMs();
        return new MockPlan(answer(userText, prompt), null, delay);
    }

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
     * <p>★ 回答末尾固定带上 {@code （上下文 N 字）} —— 这是<b>验收 RAG 的抓手</b>：
     * 脚本先断言"不带知识库时 N 很小"，再断言"接入检索后 N 明显变大"，
     * 就能证明检索到的文档**真的进了 prompt**，而不是只被检索出来就丢了。
     * （只看模型输出的措辞是证明不了这件事的。）</p>
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
        return core + "（Mock 通道 · 上下文 " + promptChars(prompt) + " 字）";
    }

    private String buildToolAnswer(String userText) {
        return "我来帮你看看合适的课程。（Mock 通道 · 工具调用）";
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

    private ChatResponse toResponse(String text, int promptTokens, int completionTokens) {
        AssistantMessage message = new AssistantMessage(text);
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
    private record MockPlan(String answer, Throwable error, int delayMs) {
    }
}
