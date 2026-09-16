package com.fitmind.aigc.prompt;

/**
 * 提示词来源。
 *
 * <p>★ 约定的关键一条：<b>读不到就返回 {@code null}，不抛异常。</b></p>
 *
 * <p>因为提示词的来源本来就是"可能没有"的（Nacos 上没配过、本地没有这个文件），
 * 这是<b>正常状态而不是异常</b>。如果这里抛异常，调用方就必须写 try-catch，
 * 而"为了让代码看起来干净"很容易被写成"catch 住忽略掉" ——
 * 那正是常见实现出问题的方式（读失败只打一行日志，缓存里保持 null，
 * 最后模型拿到一个空的 system 提示）。</p>
 *
 * <p>把"读不到"表达成返回值，调用方就<b>必须</b>处理它（下一级兜底），
 * 这在类型层面就堵住了"忘了处理"的可能。</p>
 */
public interface IPromptSource {

    /**
     * 读取提示词。
     *
     * @param key Agent 类型标识（如 {@code route} / {@code knowledge}）
     * @return 提示词内容；<b>不存在时返回 null</b>
     */
    String load(String key);

    /** 来源名称，用于日志与排查（"这版提示词到底是从哪来的"） */
    String name();
}
