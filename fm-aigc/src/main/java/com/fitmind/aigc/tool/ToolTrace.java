package com.fitmind.aigc.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一轮对话里的工具调用轨迹 —— 工具的可观测 + 卡片参数回传 + 调用次数护栏。
 *
 * <p>它和 {@code RagTrace} 是同一个套路：<b>随请求创建一个普通对象，
 * 由工具就地填充</b>，收尾时统一取出来发 PARAM 事件、落库、记日志。
 * 用对象引用而不是往 ThreadLocal / 全局 Map 里塞，是为了让类型在编译期就被检查。</p>
 *
 * <p>★ <b>为什么不用常见实现的"单机 ToolResultHolder"那种全局 Map</b>：
 * 那种做法的真正问题不是"用了内存"，而是 <b>key 只有 sessionId</b> ——
 * 同一个会话连发两轮，两个请求会互相覆盖对方的工具结果。
 * 这里根本不存在"找 key"这一步：对象是这一轮私有的，
 * <b>串号在结构上就不可能发生</b>。<br>
 * 跨进程的需求（翻历史记录时重渲染卡片）由 {@code chat_message.params} 落库解决 ——
 * 那才是它该走的路，而不是把中间状态塞进 Redis。</p>
 */
public class ToolTrace {

    /** 单轮工具调用次数上限（防模型陷入"调工具 → 不满意 → 再调"的循环） */
    private final int maxCalls;

    private final AtomicInteger callCount = new AtomicInteger();
    private final List<Invocation> invocations = Collections.synchronizedList(new ArrayList<>());
    /** ★ 前端卡片数据（课程卡片 / 待支付订单卡片），随 PARAM 事件下发 */
    private final List<Map<String, Object>> cards = Collections.synchronizedList(new ArrayList<>());

    public ToolTrace(int maxCalls) {
        this.maxCalls = Math.max(1, maxCalls);
    }

    /**
     * 取一次调用额度。
     *
     * <p>★ 这是<b>成本与体验护栏</b>：模型有时会连续调工具（查一次不满意、换个参数再查），
     * 没有上限时一轮对话可能打十几次接口。超限时工具返回一句可读的话而不是抛异常 ——
     * 抛异常会直接把对话打断，用户看到的是错误。</p>
     *
     * @return false 表示已超限，本次调用不应执行
     */
    public boolean tryAcquire() {
        return callCount.incrementAndGet() <= maxCalls;
    }

    public void record(String name, String args, String resultSummary, long costMs, boolean blocked, String error) {
        invocations.add(new Invocation(name, args, resultSummary, costMs, blocked, error));
    }

    public void addCard(Map<String, Object> card) {
        if (card != null && !card.isEmpty()) {
            cards.add(card);
        }
    }

    public boolean isEmpty() {
        return invocations.isEmpty() && cards.isEmpty();
    }

    public int getCallCount() {
        return callCount.get();
    }

    public List<Map<String, Object>> getCards() {
        return cards;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("toolCallCount", callCount.get());
        map.put("toolNames", invocations.stream().map(Invocation::name).toList());
        map.put("invocations", invocations.stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", i.name());
            m.put("args", i.args());
            m.put("result", i.result());
            m.put("costMs", i.costMs());
            if (i.blocked()) {
                m.put("blocked", true);
            }
            if (i.error() != null) {
                m.put("error", i.error());
            }
            return m;
        }).toList());
        if (!cards.isEmpty()) {
            map.put("cards", cards);
        }
        return map;
    }

    /**
     * 一次工具调用的记录。
     *
     * <p>★ {@code result} 存的是**摘要**而不是完整结果：工具可能返回上千字的课程列表，
     * 全部落库既浪费又难读。排查时真正要看的是"调用了什么、参数是什么、成功还是失败"。</p>
     */
    public record Invocation(String name, String args, String result, long costMs, boolean blocked, String error) {
    }
}
