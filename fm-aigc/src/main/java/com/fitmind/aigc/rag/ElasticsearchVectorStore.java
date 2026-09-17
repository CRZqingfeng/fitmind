package com.fitmind.aigc.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitmind.aigc.config.AigcProperties;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.core.CountRequest;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.client.indices.GetMappingsRequest;
import org.elasticsearch.client.indices.GetMappingsResponse;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
// ⚠️ 7.16 起 XContentType 从 org.elasticsearch.common.xcontent 搬到了 org.elasticsearch.xcontent，
//    用旧包名会编译不过 —— 网上大量 7.x 的示例代码还是旧包名，照抄必踩
import org.elasticsearch.xcontent.XContentType;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Elasticsearch 7.x 的向量库实现。
 *
 * <p><b>为什么自己写，不用官方的 {@code spring-ai-starter-vector-store-elasticsearch}：</b>
 * 官方实现依赖 ES 8.x 的 <b>knn 顶层查询</b>，而本项目服务端是 7.17.22
 * （fm-search 在上面跑了很久，不可能为这个模块升级 —— 升了 fm-search 的
 * {@code RestHighLevelClient} 就不兼容了）。7.17 上做向量检索只有
 * {@code script_score} + {@code cosineSimilarity} 这一条路。
 * 硬用官方 starter 的典型结局是"依赖装上了、代码编译过了、一查就报 unknown field [knn]"。</p>
 *
 * <p>★ <b>但这里实现的是 Spring AI 的 {@link VectorStore} 接口本身</b>，
 * 这一点很关键 —— 上层的 Advisor、检索服务、Agent <b>完全不知道</b>底下是 ES。
 * 将来要换 Milvus / pgvector，只需换掉这一个 Bean，
 * 其余代码一行都不用动。这就是"依赖抽象而不是依赖实现"在真实项目里的样子。</p>
 *
 * <p><b>三个实现上的关键细节</b>：
 * <ol>
 *   <li><b>分数换算</b>：{@code cosineSimilarity} 的取值是 [-1, 1]，而 ES 的
 *       {@code script_score} 要求非负 → 脚本里 {@code +1.0} 映射到 [0, 2]，
 *       取出来再 {@code -1.0} 还原成真实余弦相似度。用没换算的分数去比阈值，
 *       "相似度 0.55"就变成了事实上的 1.55（永远匹配），阈值形同虚设。</li>
 *   <li><b>ID 由语料派生</b>（{@code docId#chunkIndex}），不是随机 UUID ——
 *       于是重建索引是<b>覆盖</b>而不是"又插进去一份"，
 *       重复重建不会让检索结果出现两份同样内容。</li>
 *   <li><b>刷新策略</b>：{@code add} 不强制刷新（批量写 N 次刷 N 次很慢），
 *       由调用方在重建完成后统一 {@link #refresh()}。这也是"重建完立刻能查到"
 *       这条验收断言能成立的前提。</li>
 * </ol>
 * </p>
 */
@Slf4j
public class ElasticsearchVectorStore implements VectorStore {

    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_EMBEDDING = "embedding";
    public static final String FIELD_TITLE = "title";
    public static final String FIELD_SECTION = "section";
    public static final String FIELD_DOC_ID = "doc_id";
    public static final String FIELD_CHUNK_INDEX = "chunk_index";

    /** RRF（Reciprocal Rank Fusion）的平滑常数，取业界常用的 60 */
    private static final int RRF_K = 60;

    private final RestHighLevelClient client;
    private final EmbeddingModel embeddingModel;
    private final AigcProperties.Rag props;
    private final ObjectMapper mapper;

    public ElasticsearchVectorStore(RestHighLevelClient client, EmbeddingModel embeddingModel,
                                    AigcProperties.Rag props, ObjectMapper mapper) {
        this.client = client;
        this.embeddingModel = embeddingModel;
        this.props = props;
        this.mapper = mapper;
    }

    public String indexName() {
        return props.indexName();
    }

    // ==================== 索引生命周期 ====================

    public boolean indexExists() {
        try {
            return client.indices().exists(new GetIndexRequest(indexName()), RequestOptions.DEFAULT);
        } catch (Exception e) {
            throw new IllegalStateException("检查 ES 索引失败（ES 是否在 " + props.getEsHost() + ":" + props.getEsPort() + " ？）：" + e.getMessage(), e);
        }
    }

    /**
     * 建索引。
     *
     * <p>★ 维度是<b>算进来的</b>（{@code props.getDimensions()}），不是写死的常量 ——
     * 换 embedding 模型时如果忘了改维度，ES 会在写入时直接报
     * "vector dimension mismatch"，这比"写进去但检索不出"要好得多。</p>
     *
     * <p>{@code index: true} 是 7.x 上 dense_vector 能做检索的前提
     * （8.x 的默认值变了，这也是网上很多 mapping 例子在 7.17 上跑不动的原因）。</p>
     */
    public void createIndex() {
        int dims = props.getDimensions();
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("settings", Map.of("number_of_shards", 1, "number_of_replicas", 0));
        Map<String, Object> properties = new LinkedHashMap<>();
        // 中文必须用 IK：standard 分词器会把"增肌"切成"增/肌"两个单字，
        // 关键词一路的召回质量会明显变差（而关键词一路正是混合检索的兜底）
        properties.put(FIELD_CONTENT, Map.of(
                "type", "text", "analyzer", "ik_max_word", "search_analyzer", "ik_smart"));
        properties.put(FIELD_EMBEDDING, Map.of(
                "type", "dense_vector", "dims", dims, "index", true, "similarity", "cosine"));
        properties.put(FIELD_TITLE, Map.of(
                "type", "text", "analyzer", "ik_max_word", "search_analyzer", "ik_smart",
                "fields", Map.of("raw", Map.of("type", "keyword"))));
        properties.put(FIELD_SECTION, Map.of(
                "type", "text", "analyzer", "ik_max_word", "search_analyzer", "ik_smart"));
        properties.put(FIELD_DOC_ID, Map.of("type", "keyword"));
        properties.put(FIELD_CHUNK_INDEX, Map.of("type", "integer"));
        // 这几列是**过滤维度**，必须是 keyword 而不是 text：
        // text 会先分词再匹配，"muscle_gain" 被切成 muscle/gain 后，
        // 用精确值去过滤就永远匹配不上（而且不报错，只是过滤不生效）
        properties.put("category", Map.of("type", "keyword"));
        properties.put("goal", Map.of("type", "keyword"));
        properties.put("level", Map.of("type", "keyword"));
        properties.put("source", Map.of("type", "keyword"));
        mapping.put("mappings", Map.of("properties", properties));

        try {
            client.indices().create(new CreateIndexRequest(indexName())
                    .source(mapper.writeValueAsString(mapping), XContentType.JSON), RequestOptions.DEFAULT);
            log.info("★★ 知识库索引已创建：{}（{} 维，相似度 cosine，中文 IK 分词）", indexName(), dims);
        } catch (Exception e) {
            throw new IllegalStateException("创建索引失败：" + indexName() + " —— " + e.getMessage(), e);
        }
    }

    public void dropIndex() {
        try {
            client.indices().delete(new DeleteIndexRequest(indexName()), RequestOptions.DEFAULT);
            log.warn("知识库索引已删除：{}", indexName());
        } catch (Exception e) {
            log.warn("删除索引失败（可能本来就不存在）：{} —— {}", indexName(), e.getMessage());
        }
    }

    /**
     * 读出索引 mapping 里记录的向量维度。
     *
     * <p>★ 这是防"换了 embedding 模型但索引没重建"的那道闸门。
     * 那种情况的表现极其隐蔽：索引还在、数据还在、查询也不报错，
     * 只是<b>相关性全错</b>（新模型的向量和旧索引里的向量不在同一个空间里）。
     * 有了这个检查，启动时就能把问题喊出来。</p>
     *
     * @return 维度；{@code null} 表示读不出来（不阻塞使用，只记日志）
     */
    public Integer mappedDimensions() {
        try {
            GetMappingsResponse resp = client.indices()
                    .getMapping(new GetMappingsRequest().indices(indexName()), RequestOptions.DEFAULT);
            var metadata = resp.mappings().get(indexName());
            if (metadata == null) {
                return null;
            }
            // mapping 源码的结构在不同版本下略有差别（有的带外层 "mappings"，有的不带），
            // 所以两条路径都试 —— 读不到维度只会让"防错闸门"失效，不该让它抛异常
            var root = mapper.readTree(metadata.source().string());
            var props = root.path("mappings").path("properties");
            if (props.isMissingNode()) {
                props = root.path("properties");
            }
            var dims = props.path(FIELD_EMBEDDING).path("dims");
            return dims.isNumber() ? dims.intValue() : null;
        } catch (Exception e) {
            log.warn("读取索引维度失败（不阻塞）：{} —— {}", indexName(), e.getMessage());
            return null;
        }
    }

    public long count() {
        try {
            return client.count(new CountRequest(indexName()), RequestOptions.DEFAULT).getCount();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 立刻可见：重建完不刷新的表现为"索引建好了、条数也对，但一查就是空" */
    public void refresh() {
        try {
            client.indices().refresh(new org.elasticsearch.action.admin.indices.refresh.RefreshRequest(indexName()),
                    RequestOptions.DEFAULT);
        } catch (Exception e) {
            log.warn("索引 refresh 失败（不影响后续查询，只是延迟可见）：{}", e.getMessage());
        }
    }

    // ==================== VectorStore：写入 ====================

    @Override
    public void add(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        try {
            BulkRequest bulk = new BulkRequest();
            int ok = 0;
            for (Document doc : documents) {
                float[] vector = embeddingModel.embed(doc);
                Map<String, Object> source = new HashMap<>();
                source.put(FIELD_CONTENT, doc.getText());
                source.put(FIELD_EMBEDDING, vector);
                // 元数据原样带上：检索时要靠它做过滤（goal/category）与溯源（title/section）
                doc.getMetadata().forEach((k, v) -> {
                    if (v != null && !FIELD_EMBEDDING.equals(k)) {
                        source.put(k, v);
                    }
                });
                bulk.add(new IndexRequest(indexName())
                        .id(doc.getId())
                        .source(mapper.writeValueAsString(source), XContentType.JSON));
                ok++;
            }
            if (ok == 0) {
                return;
            }
            BulkResponse resp = client.bulk(bulk, RequestOptions.DEFAULT);
            if (resp.hasFailures()) {
                // ⚠️ 不能只看"没抛异常"就算成功：BulkResponse 的失败是**装在返回值里**的，
                //    不检查的话，一条都没写进去也会显示"写入完成"
                String first = resp.getItems().length > 0 ? resp.getItems()[0].getFailureMessage() : "unknown";
                throw new IllegalStateException("批量写入部分失败：" + first);
            }
            log.info("向量写入完成：{} 条 → {}", ok, indexName());
        } catch (Exception e) {
            throw new IllegalStateException("向量写入失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void delete(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        try {
            BulkRequest bulk = new BulkRequest();
            ids.forEach(id -> bulk.add(new DeleteRequest(indexName(), id)));
            bulk.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
            client.bulk(bulk, RequestOptions.DEFAULT);
        } catch (Exception e) {
            throw new IllegalStateException("删除向量失败：" + e.getMessage(), e);
        }
    }

    /**
     * 按元数据条件删除。
     *
     * <p>实现方式是"先查 id 再批量删"，而不是 {@code delete_by_query} ——
     * 后者要多引一个 reindex 模块，而本项目的语料规模（几百条）
     * 完全不需要那套为海量数据准备的机制。<b>为不需要的规模引依赖是负债。</b></p>
     */
    @Override
    public void delete(Filter.Expression expression) {
        if (expression == null) {
            return;
        }
        try {
            org.elasticsearch.action.search.SearchRequest req =
                    new org.elasticsearch.action.search.SearchRequest(indexName());
            SearchSourceBuilder source = new SearchSourceBuilder()
                    .query(toEsQuery(expression))
                    .size(1000)
                    .fetchSource(false);
            req.source(source);
            SearchResponse resp = client.search(req, RequestOptions.DEFAULT);
            List<String> ids = new ArrayList<>();
            for (SearchHit hit : resp.getHits().getHits()) {
                ids.add(hit.getId());
            }
            delete(ids);
        } catch (Exception e) {
            throw new IllegalStateException("按条件删除向量失败：" + e.getMessage(), e);
        }
    }

    // ==================== VectorStore：向量检索 ====================

    /**
     * 向量相似度检索。
     *
     * <p>★ 与常见实现最大的差别在这一行：<b>query 是用户真正的问题</b>，
     * 而不是空串。常见实现写的是 {@code SearchRequest.query("")} + {@code topK(999)} ——
     * 空查询在向量空间里等同于"随机取 999 条"，等价于把整个知识库塞进 prompt。
     * 那样做每轮都要为整库内容付 token、真正相关的依据还会被淹掉。</p>
     */
    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        try {
            float[] queryVector = embeddingModel.embed(request.getQuery());

            Map<String, Object> params = new HashMap<>();
            params.put("queryVector", toDoubleList(queryVector));
            Script script = new Script(ScriptType.INLINE, "painless",
                    // +1.0 是必需的：cosineSimilarity ∈ [-1,1]，而 script_score 不允许负分
                    "cosineSimilarity(params.queryVector, '" + FIELD_EMBEDDING + "') + 1.0",
                    params);

            QueryBuilder inner = request.hasFilterExpression()
                    ? QueryBuilders.boolQuery()
                        .must(QueryBuilders.matchAllQuery())
                        .filter(toEsQuery(request.getFilterExpression()))
                    : QueryBuilders.matchAllQuery();
            QueryBuilder scriptScore = QueryBuilders.scriptScoreQuery(inner, script);

            org.elasticsearch.action.search.SearchRequest esReq =
                    new org.elasticsearch.action.search.SearchRequest(indexName());
            esReq.source(new SearchSourceBuilder()
                    .query(scriptScore)
                    .size(Math.max(1, request.getTopK()))
                    // ★ 阈值直接交给 ES 的 min_score 执行（而不是取回来再在 Java 里过滤）：
                    //   min_score 是在**取 topK 之前**生效的，所以"4 条里 3 条不达标"
                    //   会返回 1 条，而不是"取回 4 条再丢掉 3 条"——
                    //   后者的行为是"明明库里有更相关的第 5 条，却因为前 4 条被丢掉而漏掉了"
                    .minScore((float) (request.getSimilarityThreshold() + 1.0)));
            return hitToDocuments(client.search(esReq, RequestOptions.DEFAULT));
        } catch (Exception e) {
            throw new IllegalStateException("向量检索失败：" + e.getMessage(), e);
        }
    }

    /**
     * 关键词召回 —— ES 特有的能力，{@link VectorStore} 抽象里没有。
     *
     * <p>★ 这是"选 ES 而不是专用向量库"顺带拿到的好处：同一个引擎既是向量库、
     * 又是全文检索引擎，于是"关键词 + 向量"混合检索<b>不需要引入第二套中间件</b>。
     * 纯向量检索在两类问题上会漏：① 精确术语（"臀桥"、"RPE 8"）；
     * ② 数字与专有名词 —— 用字面匹配反而更准。</p>
     */
    public List<Document> keywordSearch(String query, int topK, Filter.Expression filter) {
        try {
            BoolQueryBuilder bool = QueryBuilders.boolQuery();
            MatchQueryBuilder match = QueryBuilders.matchQuery(FIELD_CONTENT, query)
                    // 整句提问按 AND 匹配几乎必然为空（口语化问题里总有不在语料里的词），
                    // 60% 是"召回有保障 + 不至于全是不相关项"之间的折中
                    .minimumShouldMatch("60%");
            bool.must(match);
            if (filter != null) {
                bool.filter(toEsQuery(filter));
            }
            org.elasticsearch.action.search.SearchRequest esReq =
                    new org.elasticsearch.action.search.SearchRequest(indexName());
            esReq.source(new SearchSourceBuilder().query(bool).size(Math.max(1, topK)));
            return hitToDocuments(client.search(esReq, RequestOptions.DEFAULT));
        } catch (Exception e) {
            throw new IllegalStateException("关键词检索失败：" + e.getMessage(), e);
        }
    }

    // ==================== 融合（RRF） ====================

    /**
     * 用 RRF（Reciprocal Rank Fusion）把多路召回结果融合成一份。
     *
     * <p>★ <b>为什么用 RRF 而不是"加权平均分数"</b>：两路的分数根本不可比 ——
     * 向量是余弦相似度（[0,1]），关键词是 BM25（无上界、随语料长度变化）。
     * 要把它们加权平均，先得做归一化，而归一化又依赖"这一批结果的最大最小值"，
     * 结果就是<b>同样的文档在不同的查询里分数会变</b>，阈值变得毫无意义。
     * RRF 只用<b>排名</b>：{@code score = Σ 1/(k + rank)}，天然免疫量纲问题。</p>
     *
     * <p>副作用是好的：一路没召回到的文档仍然能被另一路带进来，
     * 而且"两路都排在前面"的文档会显著胜出 —— 这正是我们想要的语义。</p>
     */
    public static List<Document> fuseByRrf(List<List<Document>> rankedLists, int topK) {
        Map<String, Double> fused = new LinkedHashMap<>();
        Map<String, Document> docs = new LinkedHashMap<>();
        for (List<Document> list : rankedLists) {
            for (int rank = 0; rank < list.size(); rank++) {
                Document doc = list.get(rank);
                fused.merge(doc.getId(), 1.0 / (RRF_K + rank + 1), Double::sum);
                docs.putIfAbsent(doc.getId(), doc);
            }
        }
        return fused.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(e -> {
                    Document origin = docs.get(e.getKey());
                    Map<String, Object> meta = new HashMap<>(origin.getMetadata());
                    meta.put("rrf", Math.round(e.getValue() * 10000) / 10000.0);
                    // 保留原文档的向量相似度分数（关键词一路带回来的分是 BM25，不可比，
                    // 所以只把 RRF 融合分单独放 meta，不加权进 score）
                    return Document.builder()
                            .id(origin.getId())
                            .text(origin.getText())
                            .metadata(meta)
                            .score(origin.getScore())
                            .build();
                })
                .toList();
    }

    // ==================== 内部 ====================

    private List<Document> hitToDocuments(SearchResponse resp) {
        List<Document> result = new ArrayList<>();
        for (SearchHit hit : resp.getHits().getHits()) {
            Map<String, Object> source = hit.getSourceAsMap();
            if (source == null) {
                continue;
            }
            Map<String, Object> metadata = new HashMap<>(source);
            Object content = metadata.remove(FIELD_CONTENT);
            metadata.remove(FIELD_EMBEDDING);
            result.add(Document.builder()
                    .id(hit.getId())
                    .text(content == null ? "" : String.valueOf(content))
                    .metadata(metadata)
                    // 还原真实余弦相似度（ES 那边 +1.0 了）
                    .score((double) hit.getScore() - 1.0)
                    .build());
        }
        return result;
    }

    /** float[] → List<Double>：painless 脚本的参数只认 List（传 float[] 会被当成对象报错） */
    private List<Double> toDoubleList(float[] vector) {
        List<Double> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add((double) v);
        }
        return list;
    }

    /**
     * 把 Spring AI 的过滤表达式翻译成 ES 查询。
     *
     * <p>只覆盖本项目真正会用到的算子（AND/OR/NOT/EQ/NE/IN/NIN/范围比较）。
     * 不追求"支持 DSL 全集"：<b>过滤器写错时最容易出现的不是报错，而是"静默不生效"</b>
     * （多一个不该有的 should 子句，查询照样返回结果，只是没按预期过滤），
     * 所以宁可支持的少、但每个都验证过。</p>
     */
    private QueryBuilder toEsQuery(Filter.Expression expression) {
        if (expression == null) {
            return QueryBuilders.matchAllQuery();
        }
        switch (expression.type()) {
            case AND: {
                BoolQueryBuilder bool = QueryBuilders.boolQuery();
                addFilter(bool, expression.left());
                addFilter(bool, expression.right());
                return bool;
            }
            case OR: {
                BoolQueryBuilder bool = QueryBuilders.boolQuery();
                addShould(bool, expression.left());
                addShould(bool, expression.right());
                // ⚠️ must 一个 match_all 才能把 should 变成"过滤"语义（而非"影响打分"）
                bool.must(QueryBuilders.matchAllQuery()).minimumShouldMatch(1);
                return bool;
            }
            case NOT:
                return QueryBuilders.boolQuery().mustNot(toEsQuery((Filter.Expression) expression.left()));
            case EQ:
                return QueryBuilders.termQuery(key(expression), value(expression));
            case NE:
                return QueryBuilders.boolQuery().mustNot(QueryBuilders.termQuery(key(expression), value(expression)));
            case IN: {
                Object v = value(expression);
                Collection<?> values = v instanceof Collection<?> c ? c : List.of(v);
                return QueryBuilders.termsQuery(key(expression), values.toArray());
            }
            case NIN: {
                Object v = value(expression);
                Collection<?> values = v instanceof Collection<?> c ? c : List.of(v);
                return QueryBuilders.boolQuery().mustNot(
                        QueryBuilders.termsQuery(key(expression), values.toArray()));
            }
            case GT:
                return QueryBuilders.rangeQuery(key(expression)).gt(value(expression));
            case GTE:
                return QueryBuilders.rangeQuery(key(expression)).gte(value(expression));
            case LT:
                return QueryBuilders.rangeQuery(key(expression)).lt(value(expression));
            case LTE:
                return QueryBuilders.rangeQuery(key(expression)).lte(value(expression));
            default:
                throw new UnsupportedOperationException("不支持的过滤算子：" + expression.type());
        }
    }

    private void addFilter(BoolQueryBuilder bool, Filter.Operand operand) {
        QueryBuilder q = operand instanceof Filter.Expression e ? toEsQuery(e) : toEsQuery((Filter.Expression) operand);
        bool.filter(q);
    }

    private void addShould(BoolQueryBuilder bool, Filter.Operand operand) {
        bool.should(toEsQuery((Filter.Expression) operand));
    }

    private String key(Filter.Expression expression) {
        return ((Filter.Key) expression.left()).key();
    }

    private Object value(Filter.Expression expression) {
        return ((Filter.Value) expression.right()).value();
    }
}
