package com.fitmind.aigc.orchestration;

import cn.hutool.core.util.StrUtil;

/**
 * 编排策略 —— 决定"一次提问交给谁处理"。
 *
 * <p>★ 三种策略的价值不在于"有三种"，而在于：</p>
 * <ol>
 *   <li><b>用配置切换编排方式</b>这个架构能力本身（改一行配置就能换编排，不用改代码）</li>
 *   <li>可以<b>横向对比三种模式的效果与成本</b> —— 这本身就是个可演示的东西
 *       （同样的提问，三种策略分别花多少次模型调用、延迟多少、答得怎么样）</li>
 * </ol>
 */
public enum ChatType {

    /**
     * 多 Agent 路由（最完整）。
     *
     * <p>先由路由 Agent 判断意图，再分派给专职 Agent。
     * <b>代价是多一次模型调用</b>（路由本身要花一次），换来的是每个 Agent
     * 的提示词、工具、知识域都被裁剪到很窄 —— 准确率高、上下文短。</p>
     */
    ROUTE,

    /**
     * 单 Agent + 增强（链路最短）。
     *
     * <p>不分派，直接把问题交给带 RAG 的知识问答 Agent。
     * 一次模型调用，但工具与知识全塞给同一个 Agent，提示词更长、准确率略低。</p>
     */
    ENHANCE,

    /**
     * 纯模型对话（基线，什么都不加）。
     *
     * <p>★ <b>这一档是我们对常见实现的改动</b>：常见实现的第三档是
     * {@code APP} —— 调用阿里云百炼控制台上配置好的"托管应用"（需要一个 appId）。
     * 我们没有那个 appId，而且从"对比三种编排"这个目的看，
     * 真正缺的是一个<b>什么都不加的对照组</b>：
     * 有了它，"多花的那些模型调用和检索到底带来了多少提升"才有基准线。</p>
     *
     * <p>它同时是排查问题的第一站：回答不对劲时切到 SIMPLE，
     * 如果 SIMPLE 也答得不对，那是模型或提示词的问题，
     * 不用去怀疑 RAG 检索和工具调用（把问题域缩小一半）。</p>
     */
    SIMPLE,
    ;

    public static final ChatType DEFAULT = ROUTE;

    /**
     * 解析配置值。
     *
     * <p>⚠️ 配置写错时<b>不能抛异常</b>：编排策略是部署级开关，
     * 拼错一个字母就让整个 AI 服务起不来，代价和收益完全不成比例。
     * 这里选择"记下来 + 用默认值"，服务照常可用（并且启动日志里能看出来）。</p>
     */
    public static ChatType parse(String value) {
        if (StrUtil.isBlank(value)) {
            return DEFAULT;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }
}
