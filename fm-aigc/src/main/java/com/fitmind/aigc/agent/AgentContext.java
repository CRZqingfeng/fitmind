package com.fitmind.aigc.agent;

import com.fitmind.aigc.rag.RagTrace;

/**
 * 一次提问的上下文 —— Agent 回答问题所需要的全部输入。
 *
 * <p>★ <b>{@code userId} 由编排层从 {@code UserContext} 放进来，绝不来自请求参数。</b>
 * 这是 M6-4 工具调用安全的地基：工具方法不接收 userId，
 * 它需要用户身份时从这里取 —— 于是"模型编造一个 userId 去查别人的数据"这件事
 * 在结构上就不可能发生（模型压根没有给 userId 的通道）。</p>
 *
 * @param sessionId 会话id
 * @param requestId 本轮请求id（工具结果的暂存与回传用它做 key）
 * @param userId    当前登录用户（来自 UserContext）
 * @param question  用户本次提问
 * @param retrieval 检索参数（目标过滤 / topK / 阈值），可为空表示全用配置默认值
 * @param ragTrace  ★ 本轮检索轨迹（引用溯源 / 落库 / 可观测）。由编排层创建，
 *                  RAG Advisor 就地填充 —— 用对象引用传递而不是往 context 的
 *                  Map 里塞，是为了让类型在编译期就被检查
 */
public record AgentContext(String sessionId, String requestId, Long userId, String question,
                          RetrievalOptions retrieval, RagTrace ragTrace) {

    /** 不带任何覆盖参数时的便捷构造 */
    public AgentContext(String sessionId, String requestId, Long userId, String question) {
        this(sessionId, requestId, userId, question, RetrievalOptions.none(), new RagTrace());
    }

    /**
     * 检索参数覆盖。
     *
     * <p>★ <b>为什么要开放"请求级覆盖"</b>（和 {@code chatType} 是同一个理由）：
     * 调 RAG 效果的本质是"改一个参数、看命中率变了没有"。
     * 如果每个参数都要改配置 + 重启服务才能试一次，
     * 那么"调参"就会退化成"猜"—— 而"没有评估集的 RAG 调参就是瞎调"
     * 这句话的另一半是"评估集跑不动的 RAG 也调不了参"。</p>
     *
     * <p>三个字段都是 {@code null} 表示用配置默认值。它们只影响检索范围与数量，
     * <b>不参与任何鉴权判断</b>，所以由请求指定是安全的。</p>
     *
     * @param goal      训练目标（元数据过滤）。M6-4 起会由 BodyTools 从体测档案提供
     * @param topK      返回条数覆盖
     * @param threshold 相似度阈值覆盖
     * @param hybrid    是否启用"关键词 + 向量"混合检索覆盖。
     *                  ★ 开放它的唯一理由是<b>让"混合检索到底带来多少提升"变成一个可测的数字</b>：
     *                  跑同一套评估集两次（开/关），命中率的差值就是答案。
     *                  这种"方案对比"如果不能在同一进程里跑，就永远只能靠嘴说。
     */
    public record RetrievalOptions(String goal, Integer topK, Double threshold, Boolean hybrid) {

        public static RetrievalOptions none() {
            return new RetrievalOptions(null, null, null, null);
        }
    }
}
