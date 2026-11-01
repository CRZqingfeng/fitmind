package com.fitmind.aigc.agent;

import com.fitmind.aigc.prompt.PromptManager;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 定制计划 Agent —— 只产出"骨架 + 三句文案"。
 *
 * <p><b>本类最容易写错的地方：给它挂工具。</b>
 * 它看起来"需要读用户档案"，很自然会想挂 {@code BodyTools}；
 * 但骨架接口是<b>同步</b>的，而工具循环每多一轮就多一次模型往返（5~20 秒），
 * 用户点「定制计划」后要干等。所以档案由 {@code PlanSkeletonService} 直接拼进提问文本
 * —— 一次调用出结果，没有工具循环。</p>
 *
 * <p>★ <b>它不产出任何 id</b>：动作、食物、计划 id 全部由 fm-plan 从库里选。
 * 模型就算"想给"，也没有字段能放（解析器只认白名单里的 7 个 key）。</p>
 *
 * <p>★ <b>它不会被意图路由到</b>：{@code AgentType.parse()} 里排除了 PLAN，
 * {@code route.txt} 里也没有它。用户在聊天里说"帮我定制个计划"仍然走课程推荐 ——
 * 否则这段给前端解析的 JSON 会被直接念给用户看。</p>
 */
@Component
public class PlanAgent extends AbstractAgent {

    /**
     * 输出格式示例（作为 {@code {schema}} 的值传给提示词）。
     *
     * ★ 它必须与 {@code PlanSkeletonServiceImpl.parse()} 的校验口径一致：
     * 枚举值合法、ratio 之和正好 1。写错的话 mock 通道下会稳定降级。
     */
    private static final String SCHEMA_EXAMPLE = """
            {"division":"三分化","weeklyFrequency":4,"intensity":"MEDIUM",
             "mealSplit":[{"mealType":1,"ratio":0.25},{"mealType":2,"ratio":0.45},
                          {"mealType":3,"ratio":0.30}],
             "dietAdvice":"一句话饮食建议","trainingAdvice":"一句话训练建议","targetTip":"一句话目标提示"}
            """;

    public PlanAgent(ChatClient chatClient, PromptManager promptManager,
                     ToolCallingManager toolCallingManager) {
        super(chatClient, promptManager, toolCallingManager);
    }

    @Override
    public AgentType type() {
        return AgentType.PLAN;
    }

    /**
     * ★ 把"输出格式示例"作为**模板参数**传进去，而不是写死在提示词里。
     *
     * <p>踩过的坑：Spring AI 的 system 文本会经过 ST 模板引擎，提示词里直接写
     * {@code {"division": ...}} 会被当成占位符 {@code {division}}，
     * 报 <b>"The template string is not valid"</b> 并让整次调用失败 ——
     * 而它在业务上表现成"每次骨架都降级"，日志里只有一句"骨架生成失败"，
     * 很容易被误判成"模型不行"。</p>
     *
     * <p>模板参数的值<b>不会被二次解析</b>，所以把带花括号的示例放在这里既安全又唯一。</p>
     */
    @Override
    protected Map<String, Object> systemParams(AgentContext context) {
        Map<String, Object> params = new HashMap<>(super.systemParams(context));
        params.put("schema", SCHEMA_EXAMPLE);
        return params;
    }

    /**
     * 同步拿一段完整文本（骨架接口不是流式：前端要的是完整 JSON 才能解析）。
     *
     * <p>★ 为什么放在 Agent 而不是 Service 里直接 {@code chatClient.prompt()}：
     * "怎么组装一次调用"（system 提示词 + 通用安全边界 + {now} 参数）在
     * {@link AbstractAgent#request} 里只有一份实现，绕过它就等于复制一份规矩。</p>
     */
    public String complete(AgentContext context) {
        String content = request(context).call().content();
        return content == null ? "" : content;
    }
}
