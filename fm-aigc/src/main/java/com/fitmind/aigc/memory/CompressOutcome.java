package com.fitmind.aigc.memory;

import java.util.List;

/**
 * 一次上下文压缩的结果。
 *
 * <p>★ 为什么要把它作为返回值而不是只打日志：<b>压缩是一个会花钱、会加延迟的动作</b>
 * （多一次模型调用，而且它阻塞在首字之前）。既然有成本，就必须<b>可观测、可断言</b> ——
 * 否则"压缩到底有没有生效"只能靠读代码猜（常见实现就是栽在这：
 * 它的压缩条件在流式路径下永远不成立，而没有任何地方能看出来）。</p>
 *
 * @param compressed     本次是否真的压缩了
 * @param compressedCount 被压缩掉的消息条数（0 表示没压缩）
 * @param promptTokens   压缩调用消耗的输入 token
 * @param completionTokens 压缩调用消耗的输出 token
 * @param summaryChars   压缩后摘要的总长度
 * @param costMs         压缩耗时（毫秒）—— 这段延迟直接加在用户的首字等待上
 */
public record CompressOutcome(boolean compressed, int compressedCount, int promptTokens,
                              int completionTokens, int summaryChars, long costMs) {

    /** 没有发生压缩（绝大多数轮次都是这个） */
    public static CompressOutcome none() {
        return new CompressOutcome(false, 0, 0, 0, 0, 0L);
    }

    /** 取一批消息的字符总数（触发条件之一，见 AigcProperties.Memory.Compress） */
    public static int charsOf(List<MemoryMessage> messages) {
        int total = 0;
        for (MemoryMessage m : messages) {
            total += m.content() == null ? 0 : m.content().length();
        }
        return total;
    }
}
