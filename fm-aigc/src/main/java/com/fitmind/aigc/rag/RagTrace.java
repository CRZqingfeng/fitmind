package com.fitmind.aigc.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一轮对话的检索轨迹 —— 引用溯源与可观测的载体。
 *
 * <p>★ <b>为什么用"随请求创建的普通对象"，而不是往 ChatClient 的 context 里塞 Map</b>：
 * 检索结果是<b>这一轮独有</b>的，而 ChatClient 的 context 是"沿 Advisor 链传递的字符串键值对"，
 * 用它传一个结构化对象需要序列化/取值的来回转换，而取值处的强转一旦类型不匹配
 * 就是运行期 ClassCastException。直接持有一个对象引用，编译期就检查完了 ——
 * <b>能靠类型系统防的错，不要靠约定去防。</b></p>
 *
 * <p>它同时承载三件事：① 回答里要展示的引用来源；② 落库（{@code chat_message.params}）
 * 让历史记录能重渲染；③ 可观测指标（命中数、检索耗时）—— 改检索参数后
 * "命中数变了没有"就是最直接的反馈。</p>
 */
public class RagTrace {

    private List<Source> sources = new ArrayList<>();
    private int candidateCount;
    private int injectedCount;
    private int contextChars;
    private long searchMs;
    private boolean hybrid;
    private String mode = "off";
    private String note;

    /**
     * ★★ 本轮检索是"失败了"还是"查到了但没有相关内容"。
     *
     * <p>这两件事在数据上都是"0 条结果"，但对模型要说完全不同的话：
     * 失败 → 照常回答并说明没能查证；为空 → 拒答。
     * 所以必须在轨迹里把它区分出来，否则下游只能看到"空"这一个信号。</p>
     */
    private boolean failed;

    /**
     * ★ 本轮是否已经检索过 + 检索结果缓存。
     *
     * <p><b>为什么需要它</b>：工具调用会让 Advisor 链被<b>多次穿过</b>
     * （每调一轮工具就重新走一遍链）。如果没有缓存，一次"查课程"的对话
     * 会把同一个问题往 ES 里查 3~4 遍 —— 浪费 embedding 调用、拖慢响应，
     * 而且后一次的检索结果会覆盖前一次的溯源信息（用户看到 4 条依据，
     * 但回答其实基于第 1 次的）。</p>
     *
     * <p>同一个请求里，问题没变 → 检索结果必然相同 → 缓存是安全的。</p>
     */
    private volatile boolean retrieved;
    private volatile List<org.springframework.ai.document.Document> cachedDocs = List.of();

    public boolean isRetrieved() {
        return retrieved;
    }

    public List<org.springframework.ai.document.Document> cachedDocs() {
        return cachedDocs;
    }

    public void cacheDocs(List<org.springframework.ai.document.Document> docs) {
        this.cachedDocs = docs == null ? List.of() : docs;
        this.retrieved = true;
    }

    /** 检索到的、真正进了 prompt 的一条依据 */
    public record Source(String docId, String title, String section, Double score, String preview) {
    }

    public RagTrace record(List<Source> sources, int candidateCount, int injectedCount,
                           int contextChars, long searchMs, boolean hybrid) {
        this.sources = sources;
        this.candidateCount = candidateCount;
        this.injectedCount = injectedCount;
        this.contextChars = contextChars;
        this.searchMs = searchMs;
        this.hybrid = hybrid;
        this.mode = "rag";
        this.note = null;
        return this;
    }

    /** 检索不可用（ES 挂了 / embedding 失败）时的降级记录：对话继续，但标明"本轮没有依据" */
    /** 标记为"检索失败"（与"检索到空"区分开，见 {@link #failed}） */
    public RagTrace markFailed(String reason) {
        this.failed = true;
        return degrade(reason);
    }

    public boolean isFailed() {
        return failed;
    }

    public RagTrace degrade(String reason) {
        this.sources = new ArrayList<>();
        this.mode = "degraded";
        this.note = reason;
        return this;
    }

    /** 用户没走知识问答 Agent（SIMPLE / ROUTE 到别的 Agent）时的标记 */
    public RagTrace skip(String reason) {
        this.mode = "skipped";
        this.note = reason;
        return this;
    }

    public Map<String, Object> toMap() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Source s : sources) {
            // ★ 这里必须用 LinkedHashMap 而不是 Map.of：Map.of 不接受 null，
            //   而"只被关键词召回的来源没有余弦分"是正常情况。
            //   ❌ 曾经的写法是 `s.score() == null ? 0.0 : s.score()` —— 于是
            //      "没有分"被显示成"相似度 0"：一条**真的被用进 prompt 的依据**
            //      看上去像完全不相关，比不显示更误导。
            //   现在：有余弦分才输出 score，没有就不输出这个字段（前端显示"—"）。
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("docId", s.docId() == null ? "" : s.docId());
            item.put("title", s.title() == null ? "" : s.title());
            item.put("section", s.section() == null ? "" : s.section());
            if (s.score() != null) {
                item.put("score", s.score());
            }
            item.put("preview", s.preview() == null ? "" : s.preview());
            list.add(item);
        }
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("mode", mode);
        map.put("hybrid", hybrid);
        map.put("candidateCount", candidateCount);
        map.put("injectedCount", injectedCount);
        map.put("contextChars", contextChars);
        map.put("searchMs", searchMs);
        map.put("sources", list);
        if (note != null) {
            map.put("note", note);
        }
        return map;
    }

    public List<Source> getSources() {
        return sources;
    }

    public String getMode() {
        return mode;
    }

    public int getCandidateCount() {
        return candidateCount;
    }

    public int getInjectedCount() {
        return injectedCount;
    }

    public int getContextChars() {
        return contextChars;
    }

    public long getSearchMs() {
        return searchMs;
    }

    public String getNote() {
        return note;
    }
}
