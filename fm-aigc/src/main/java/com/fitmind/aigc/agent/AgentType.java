package com.fitmind.aigc.agent;

import cn.hutool.core.util.StrUtil;

/**
 * 专职 Agent 的类型。
 *
 * <p>每个类型对应一个 {@link Agent} 实现、一份 system 提示词（Nacos 外置）、
 * 以及一组专属工具。路由的产物就是它。</p>
 */
public enum AgentType {

    /** 意图路由：判断用户问的是哪一类，本身不回答业务问题 */
    ROUTE("route", "意图路由"),
    /** 课程咨询：课程详情、训练安排、注意事项（查实时数据，不查知识库） */
    CONSULT("consult", "课程咨询"),
    /** 课程推荐：按身体档案与目标推荐（★ 接 M5 的体测数据） */
    RECOMMEND("recommend", "课程推荐"),
    /** 购买引导：预下单 */
    BUY("buy", "购买引导"),
    /** ★ 健身知识问答：真正的 RAG（训练/营养/康复的知识性问题） */
    KNOWLEDGE("knowledge", "健身知识问答"),
    ;

    /** 小写标识：用于 prompt 文件名、Nacos dataId、落库的 agent_type */
    private final String key;
    private final String desc;

    AgentType(String key, String desc) {
        this.key = key;
        this.desc = desc;
    }

    public String key() {
        return key;
    }

    public String desc() {
        return desc;
    }

    /**
     * 从模型输出里解析出 Agent 类型。
     *
     * <p>★ <b>这个方法的全部难点在容错，因为它的输入是不可控的。</b>
     * 路由 Agent 被要求"只输出一个词"，但真实模型经常多输出一点东西
     * （"这是课程咨询类问题：CONSULT"、带引号、带句号、输出中文……）。
     * 如果按"精确等于某个枚举名"来判断，这些都会解析失败 ——
     * 而失败的后果是<b>每次提问都掉进兜底分支</b>，看起来"路由根本没生效"。</p>
     *
     * <p>所以这里做了三层容错：① 英文枚举名包含匹配（忽略大小写）
     * ② 中文关键词匹配 ③ 全部失败时兜底到知识问答
     * （候选项里它最"泛用"，答不上来也不会给出错误的业务动作）。</p>
     *
     * <p>⚠️ 注意 <b>ROUTE 自己不在可选结果里</b>：它是"路由者"，不是"被路由到的目标"。
     * 如果模型输出了 ROUTE，说明它没理解任务，此时兜底比执行更安全。</p>
     */
    public static AgentType parse(String modelOutput) {
        if (StrUtil.isBlank(modelOutput)) {
            return KNOWLEDGE;
        }
        String text = modelOutput.trim().toUpperCase();

        // ① 英文枚举名（先排除 ROUTE —— 它不是合法的路由结果）
        for (AgentType v : values()) {
            if (v != ROUTE && text.contains(v.name())) {
                return v;
            }
        }
        // ② 中文别名（模型有时会用中文回答）
        if (text.contains("推荐") || text.contains("建议课程")) {
            return RECOMMEND;
        }
        if (text.contains("购买") || text.contains("下单") || text.contains("报名") || text.contains("支付")) {
            return BUY;
        }
        if (text.contains("咨询") || text.contains("课程详情") || text.contains("课程信息")) {
            return CONSULT;
        }
        if (text.contains("知识") || text.contains("科普") || text.contains("原理")) {
            return KNOWLEDGE;
        }
        // ③ 兜底
        return KNOWLEDGE;
    }
}
