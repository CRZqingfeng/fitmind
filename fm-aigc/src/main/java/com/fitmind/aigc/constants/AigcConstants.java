package com.fitmind.aigc.constants;

/**
 * AI 模块常量。
 *
 * <p>集中在这里的原因和别的模块一样：这些值**跨类、跨测试、跨文档**都要用，
 * 散落着写早晚会出现"代码改了一处、注释和脚本还写着旧值"。</p>
 */
public final class AigcConstants {

    private AigcConstants() {
    }

    // ==================== 模型通道 ====================

    /** DeepSeek（真实模型，OpenAI 兼容协议） */
    public static final String PROVIDER_DEEPSEEK = "deepseek";
    /** Mock（内置确定性模型，供自动化验收与离线演示） */
    public static final String PROVIDER_MOCK = "mock";

    // ==================== 消息角色 ====================

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    // ==================== SSE 事件类型 ====================

    /**
     * 增量文本（打字机效果）。
     */
    public static final String EVENT_DATA = "DATA";
    /**
     * 结构化参数（工具调用产出的卡片数据，前端据此渲染课程卡片）。
     */
    public static final String EVENT_PARAM = "PARAM";
    /**
     * 本轮结束。前端收到它就关闭连接。
     *
     * <p>★ 为什么要有独立的结束事件，而不是"连接断开即结束"：
     * 流式响应中"正常结束"和"网络断开"在客户端看来都是 onerror/onclose，
     * 无法区分。显式的 STOP 让前端能区分**"回答完了"**和**"掉线了"** ——
     * 后者应该提示重试，前者不该提示。</p>
     */
    public static final String EVENT_STOP = "STOP";

    // ==================== Redis key ====================

    /**
     * Mock 通道的 prompt 快照列表（供验收脚本断言"检索到的文档进了 prompt"）。
     *
     * <p>★ 这里有个刻意的设计：快照存 Redis 而不是只放在内存里。
     * 内存 Map 在单实例时够用，但验收脚本要读它就得暴露一个接口，
     * 而那个接口本身又要处理鉴权（脚本走网关会 401）。
     * 存 Redis 后脚本直接读库即可 —— **测试用的旁路通道不要占用业务接口**。</p>
     */
    public static final String MOCK_PROMPT_SNAPSHOT_KEY = "aigc:mock:prompts";
    /** prompt 快照保留条数 */
    public static final int MOCK_PROMPT_SNAPSHOT_LIMIT = 30;

    // ==================== 默认值 ====================

    /** 会话标题取自首轮提问的前 N 个字 */
    public static final int SESSION_TITLE_MAX_LEN = 20;
    /** 会话列表默认页大小上限 */
    public static final int SESSION_PAGE_MAX_SIZE = 50;

    // ==================== 降级文案 ====================

    /**
     * 模型超时/不可用时的兜底回答。
     *
     * <p>★ 注意这是**返回到对话里的助手消息**，而不是抛 500。
     * 理由：AI 对话里"模型暂时不可用"是**预期内的业务状态**（限流、超时、
     * 余额不足都可能），把它变成 500 会让前端只能弹一个"系统错误"，
     * 用户不知道能不能重试。写成一条可读的助手消息，用户能看懂、能重试，
     * 而且这条消息会**落库** —— 出了问题能查到当时到底发生了什么。</p>
     */
    public static final String FALLBACK_MODEL_BUSY =
            "AI 教练这边暂时忙不过来，稍等一会儿再问我一次吧。";
}
