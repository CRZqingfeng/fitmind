package com.fitmind.aigc.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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

    /**
     * ★★ 收一张卡片 —— <b>同类型卡片在这里就地合并/去重</b>（M6-8 #15）。
     *
     * <p><b>为什么必须治</b>：一轮对话里工具可能被调好几次（查一次不满意、换个词再查），
     * 每次都 {@code addCard} 一张 {@code COURSE_LIST} —— 于是前端**一张一张往上堆**，
     * 同一个课程在两三次搜索里重复出现。用户看到的是"聊着聊着刷出一屏卡片"，
     * 而其中一半是重复信息。</p>
     *
     * <p><b>为什么在这一层治（而不是前端 / 不是工具里）</b>：</p>
     * <ul>
     *   <li><b>不在前端</b>：前端只知道"收到了 3 张卡片"，并不知道它们**为什么**是三张
     *       （是三次不同搜索，还是同一批数据重发）。要合并就得先猜这个语义。</li>
     *   <li><b>不在工具里</b>：工具是"一次调用一份结果"，它**看不到同轮的其他调用**
     *       —— 让工具去重等于要求它跨调用共享状态，那才是真的把简单事做复杂。</li>
     *   <li><b>在 trace 层刚好</b>：trace 本来就是"这一轮的私有容器"，它**天然知道整轮的全貌**，
     *       而且改它**不动模型契约、不动 SSE 协议**（前端收到的仍是一个 cards 数组）。</li>
     * </ul>
     *
     * <p><b>两条合并规则</b>：</p>
     * <ol>
     *   <li>{@code COURSE_LIST}：**合并成一张**，同一 {@code courseId} 只留一次
     *       （先出现的保留，顺序即"相关性顺序"）；</li>
     *   <li>{@code ORDER_CONFIRM}：**只保留最后一张** —— 订单卡片是"当前待确认的这笔"，
     *       前面的那笔已经作废了，留着只会让用户点错。</li>
     * </ol>
     */
    public void addCard(Map<String, Object> card) {
        if (card == null || card.isEmpty()) {
            return;
        }
        // ★ 复合操作必须整体同步：cards 本身是 synchronizedList，但"先查再写"不是原子的
        synchronized (cards) {
            String type = String.valueOf(card.get("type"));
            if (COURSE_LIST.equals(type)) {
                Map<String, Object> existing = cards.stream()
                        .filter(c -> COURSE_LIST.equals(String.valueOf(c.get("type"))))
                        .findFirst()
                        .orElse(null);
                if (existing != null) {
                    mergeCourseList(existing, card);
                    return;
                }
            } else if (ORDER_CONFIRM.equals(type)) {
                // 只留最后一张：旧的那笔订单已经不该被点了
                cards.removeIf(c -> ORDER_CONFIRM.equals(String.valueOf(c.get("type"))));
            }
            cards.add(card);
        }
    }

    /** 卡片类型（与前端 {@code ChatCard} 的渲染分支对齐） */
    private static final String COURSE_LIST = "COURSE_LIST";
    private static final String ORDER_CONFIRM = "ORDER_CONFIRM";

    /**
     * 把新搜到的课程并进已有那张 {@code COURSE_LIST}，**按 {@code courseId} 去重**。
     *
     * <p>★ 保留"先出现的那一份"：先命中说明它在这轮检索里更相关，
     * 后来的搜索不该把它顶掉。所以这里只做"补"，不做"覆盖"。</p>
     *
     * <p>★ 关键词也累加（"哑铃 + 增肌"），因为这张卡现在是**两次搜索的并集** ——
     * 标题只说一个关键词会让人以为结果丢了。</p>
     */
    @SuppressWarnings("unchecked")
    private void mergeCourseList(Map<String, Object> existing, Map<String, Object> incoming) {
        List<Map<String, Object>> target =
                (List<Map<String, Object>>) existing.computeIfAbsent("courses", k -> new ArrayList<>());
        Set<Object> seen = target.stream()
                .map(c -> c.get("courseId"))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Object incomingCourses = incoming.get("courses");
        if (incomingCourses instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("courseId") != null && seen.add(m.get("courseId"))) {
                    target.add((Map<String, Object>) m);
                }
            }
        }
        // 关键词累加（去重、保持出现顺序）
        Set<String> keywords = new LinkedHashSet<>();
        for (Map<String, Object> src : List.of(existing, incoming)) {
            String kw = src.get("keyword") == null ? null : String.valueOf(src.get("keyword"));
            if (kw != null && !kw.isBlank()) {
                keywords.add(kw);
            }
        }
        if (!keywords.isEmpty()) {
            existing.put("keyword", String.join(" + ", keywords));
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
