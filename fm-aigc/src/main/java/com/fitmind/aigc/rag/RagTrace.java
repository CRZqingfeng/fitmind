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
            list.add(Map.of(
                    "docId", s.docId() == null ? "" : s.docId(),
                    "title", s.title() == null ? "" : s.title(),
                    "section", s.section() == null ? "" : s.section(),
                    "score", s.score() == null ? 0.0 : s.score(),
                    "preview", s.preview() == null ? "" : s.preview()));
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
