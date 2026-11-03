package com.fitmind.aigc.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitmind.aigc.config.AigcProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重排器 —— 用 cross-encoder 对候选文档重新打分。
 *
 * <p><b>为什么 RRF 不够</b>：RRF 融合只看"名次"（{@code 1/(k+rank)}），
 * 它对量纲免疫、稳定、便宜，但它<b>不知道这块内容到底答不答得上问题</b>。
 * 语料只有 1.4 万字时这不是问题（一个主题基本只有一篇文档讲）；
 * 扩到 4.3 万字、155 块之后，"字面很像"的块会明显变多 ——
 * 实测上下文精度从 0.89 掉到 0.75，而 docId 命中率仍是 100%，
 * 即<b>不是漏了文档，是名额被"像但没用"的块占了</b>。</p>
 *
 * <p><b>为什么不自己训一个</b>：百炼的 gte-rerank-v2 是现成的 cross-encoder，
 * 一次调用约 0.005 元、200ms 级延迟，比自己集成 ONNX + 下载 600MB 模型划算得多。
 * 代价是"多了一个外部依赖"，所以本类的第一原则是<b>失败必须降级</b>：
 * 重排挂了就按 RRF 原顺序返回，绝不因为这一层把检索搞挂。</p>
 *
 * <p><b>为什么截断到 1200 字再送</b>：重排按 token 计费，而一块正文只有 600 字左右，
 * 1200 是"够判断、不浪费"的上限；真遇到被硬切的长块，前 1200 字也足够判断主题。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Reranker {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 单块送去重排的最大字符数（控制 token 成本） */
    private static final int MAX_DOC_CHARS = 1200;

    /** 连接超时与读取超时分开：连接不上要快速失败，读取慢则按配置等 */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final AigcProperties props;

    /**
     * 对候选重排。
     *
     * @param query 用户原始问题（不要传改写后的，重排要判断"这段能不能回答用户的这句话"）
     * @param docs  RRF 融合后的候选（顺序即 RRF 排名）
     * @return 按重排分数降序的文档；重排关闭或失败时<b>原样返回</b>
     */
    public List<Document> rerank(String query, List<Document> docs) {
        AigcProperties.Rag rag = props.getRag();
        if (!rag.isRerankEnabled() || docs == null || docs.size() <= 1) {
            return docs;
        }
        try {
            List<String> texts = docs.stream()
                    .map(d -> {
                        String text = d.getText() == null ? "" : d.getText();
                        return text.length() > MAX_DOC_CHARS ? text.substring(0, MAX_DOC_CHARS) : text;
                    })
                    .toList();

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("query", query);
            input.put("documents", texts);
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("return_documents", false);
            parameters.put("top_n", docs.size());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", rag.getRerankModel());
            body.put("input", input);
            body.put("parameters", parameters);

            HttpRequest request = HttpRequest.newBuilder(URI.create(rag.getRerankUrl()))
                    .timeout(Duration.ofMillis(rag.getRerankTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + rag.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();

            long start = System.currentTimeMillis();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            long cost = System.currentTimeMillis() - start;

            if (response.statusCode() != 200) {
                log.warn("重排失败（HTTP {}，降级为 RRF 顺序）：{}", response.statusCode(),
                        brief(response.body()));
                return docs;
            }

            JsonNode results = JSON.readTree(response.body()).path("output").path("results");
            List<Document> sorted = new ArrayList<>();
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= docs.size()) {
                    continue;
                }
                Document origin = docs.get(index);
                Map<String, Object> meta = new HashMap<>(origin.getMetadata());
                meta.put("rerank", item.path("relevance_score").asDouble());
                sorted.add(Document.builder()
                        .id(origin.getId())
                        .text(origin.getText())
                        .metadata(meta)
                        // ★ 保留原向量分数（余弦），重排分单独放 meta：
                        //   两者量纲不同，混进同一个 score 字段会重演 #A33 的坑
                        .score(origin.getScore())
                        .build());
            }
            if (sorted.isEmpty()) {
                log.warn("重排返回空结果（降级为 RRF 顺序）：{}", brief(response.body()));
                return docs;
            }
            log.debug("重排完成：{} 条候选 → top1={}，{}ms", docs.size(),
                    sorted.get(0).getMetadata().get("rerank"), cost);
            return sorted;
        } catch (Exception e) {
            // ★ 降级而不是抛：重排是"锦上添花"，不该让检索（乃至对话）失败
            log.warn("重排异常（降级为 RRF 顺序）：{}", e.getMessage());
            return docs;
        }
    }

    private String brief(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }
}
