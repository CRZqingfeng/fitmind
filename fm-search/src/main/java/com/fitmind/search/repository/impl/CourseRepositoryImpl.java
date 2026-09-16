package com.fitmind.search.repository.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.search.constants.EsConstants;
import com.fitmind.search.constants.SearchErrorInfo;
import com.fitmind.search.domain.po.CourseDoc;
import com.fitmind.search.domain.query.CourseSearchQuery;
import com.fitmind.search.repository.CourseRepository;
import com.fitmind.search.config.IndexInitializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.lucene.search.function.CombineFunction;
import org.elasticsearch.common.lucene.search.function.FieldValueFactorFunction;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder;
import org.elasticsearch.index.query.functionscore.ScoreFunctionBuilders;
// ⚠️ ES 7.17 起 XContentType 从 org.elasticsearch.common.xcontent 挪到了 org.elasticsearch.xcontent
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 课程索引仓储实现（ES 7 RestHighLevelClient）。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class CourseRepositoryImpl implements CourseRepository {

    /** 多字段匹配时各字段的权重：课程名最优先，其次是动作名和教练名 */
    private static final Map<String, Float> SEARCH_FIELDS = Map.of(
            EsConstants.Field.NAME, 3.0f,
            EsConstants.Field.ACTION_NAMES, 2.0f,
            EsConstants.Field.TEACHER_NAMES, 2.0f,
            EsConstants.Field.CATEGORY_PATH, 1.0f,
            EsConstants.Field.TRAINING_DAYS, 1.0f
    );

    private final RestHighLevelClient client;
    private final ObjectMapper esObjectMapper;
    private final IndexInitializer indexInitializer;

    /** 索引是否已确认存在（避免每次查询都去问一次 ES） */
    private volatile boolean indexEnsured = false;

    @Value("${elasticsearch.index:fitmind_course}")
    private String indexName;

    // ==================== 写 ====================

    @Override
    public void save(CourseDoc doc) {
        try {
            ensureIndex();
            IndexRequest request = new IndexRequest(indexName)
                    .id(String.valueOf(doc.getId()))
                    .source(esObjectMapper.writeValueAsString(doc), XContentType.JSON);
            client.index(request, RequestOptions.DEFAULT);
            log.debug("索引写入成功：courseId={}", doc.getId());
        } catch (IOException e) {
            throw new BizIllegalException(SearchErrorInfo.Msg.INDEX_WRITE_FAILED);
        }
    }

    @Override
    public void deleteById(Long id) {
        try {
            ensureIndex();
            client.delete(new DeleteRequest(indexName, String.valueOf(id)), RequestOptions.DEFAULT);
            log.debug("索引删除成功：courseId={}", id);
        } catch (IOException e) {
            throw new BizIllegalException(SearchErrorInfo.Msg.INDEX_WRITE_FAILED);
        }
    }

    @Override
    public boolean exists(Long id) {
        try {
            ensureIndex();
            return client.exists(new GetRequest(indexName, String.valueOf(id)), RequestOptions.DEFAULT);
        } catch (IOException e) {
            throw new BizIllegalException(SearchErrorInfo.Msg.INDEX_QUERY_FAILED);
        }
    }

    // ==================== 读 ====================

    @Override
    public SearchPage search(CourseSearchQuery query) {
        try {
            if (!indexReady()) {
                return SearchPage.empty();
            }
            SearchSourceBuilder source = new SearchSourceBuilder()
                    .query(buildQuery(query))
                    .from(query.from())
                    .size(query.getPageSize())
                    .highlighter(buildHighlight())
                    .trackTotalHits(true);
            applySort(source, query);

            SearchResponse response = client.search(new SearchRequest(indexName).source(source), RequestOptions.DEFAULT);
            return toPage(response);
        } catch (IOException | ElasticsearchException e) {
            // 索引不存在不算错误——课程还没上架过，返回空结果更合理
            if (e.getMessage() != null && e.getMessage().contains("index_not_found_exception")) {
                log.warn("索引 {} 不存在，返回空结果，下次请求将重新补建", indexName);
                resetIndexFlag();
                return SearchPage.empty();
            }
            log.error("检索失败：{}", e.getMessage());
            throw new BizIllegalException(SearchErrorInfo.Msg.INDEX_QUERY_FAILED);
        }
    }

    @Override
    public List<Long> queryIdsByKeyword(String keyword, int size) {
        if (!StringUtils.hasText(keyword) || !indexReady()) {
            return List.of();
        }
        try {
            SearchSourceBuilder source = new SearchSourceBuilder()
                    .query(QueryBuilders.multiMatchQuery(keyword, EsConstants.Field.NAME))
                    .size(size)
                    .fetchSource(new String[]{EsConstants.Field.ID}, null);
            SearchResponse response = client.search(new SearchRequest(indexName).source(source), RequestOptions.DEFAULT);
            List<Long> ids = new ArrayList<>();
            for (SearchHit hit : response.getHits().getHits()) {
                ids.add(Long.valueOf(hit.getId()));
            }
            return ids;
        } catch (IOException | ElasticsearchException e) {
            log.error("按关键词查询课程id失败：{}", e.getMessage());
            throw new BizIllegalException(SearchErrorInfo.Msg.INDEX_QUERY_FAILED);
        }
    }

    @Override
    public List<CourseDoc> topBest(int size) {
        // 综合最优 = 评分 + 报名数加权。
        // 用 log1p 是因为评分和报名数的量级差很大（评分 0~100，报名数可能上千），
        // 直接相加的话报名数会完全主导排序。
        FunctionScoreQueryBuilder weighted = QueryBuilders.functionScoreQuery(
                        QueryBuilders.matchAllQuery(),
                        new FunctionScoreQueryBuilder.FilterFunctionBuilder[]{
                                new FunctionScoreQueryBuilder.FilterFunctionBuilder(
                                        ScoreFunctionBuilders.fieldValueFactorFunction(EsConstants.Field.SCORE)
                                                .factor(1.0f)
                                                .modifier(FieldValueFactorFunction.Modifier.LOG1P)
                                                .missing(0.0)),
                                new FunctionScoreQueryBuilder.FilterFunctionBuilder(
                                        ScoreFunctionBuilders.fieldValueFactorFunction(EsConstants.Field.SOLD)
                                                .factor(0.5f)
                                                .modifier(FieldValueFactorFunction.Modifier.LOG1P)
                                                .missing(0.0))
                        })
                .boostMode(CombineFunction.SUM);
        return simpleQuery(weighted, null, size);
    }

    @Override
    public List<CourseDoc> topNew(int size) {
        return simpleQuery(QueryBuilders.matchAllQuery(), EsConstants.Field.PUBLISH_TIME, size);
    }

    @Override
    public List<CourseDoc> topFree(int size) {
        return simpleQuery(QueryBuilders.termQuery(EsConstants.Field.FREE, true), null, size);
    }

    @Override
    public List<CourseDoc> topByCategories(List<Long> categoryIds, int size) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return List.of();
        }
        // 分类可能落在一/二/三级中的任何一级，所以用 should 做"命中任一即可"
        BoolQueryBuilder bool = QueryBuilders.boolQuery().minimumShouldMatch(1);
        for (Long id : categoryIds) {
            if (id == null) {
                continue;
            }
            bool.should(QueryBuilders.termQuery(EsConstants.Field.CATEGORY_ID_LV1, id))
                    .should(QueryBuilders.termQuery(EsConstants.Field.CATEGORY_ID_LV2, id))
                    .should(QueryBuilders.termQuery(EsConstants.Field.CATEGORY_ID_LV3, id));
        }
        return simpleQuery(bool, null, size);
    }

    // ==================== 内部方法 ====================

    /**
     * 构造查询条件：关键词走多字段匹配，其余全部走 filter（不参与打分，还能命中缓存）。
     */
    private BoolQueryBuilder buildQuery(CourseSearchQuery query) {
        BoolQueryBuilder bool = QueryBuilders.boolQuery();
        if (StringUtils.hasText(query.getKeyword())) {
            MultiMatchQueryBuilder match = QueryBuilders.multiMatchQuery(query.getKeyword());
            SEARCH_FIELDS.forEach((field, boost) -> match.field(field, boost));
            bool.must(match);
        } else {
            bool.must(QueryBuilders.matchAllQuery());
        }

        // 以下都是精确过滤，用 filter 而不是 must：不打分、结果可被 ES 缓存
        addTerm(bool, EsConstants.Field.CATEGORY_ID_LV1, query.getCategoryIdLv1());
        addTerm(bool, EsConstants.Field.CATEGORY_ID_LV2, query.getCategoryIdLv2());
        addTerm(bool, EsConstants.Field.CATEGORY_ID_LV3, query.getCategoryIdLv3());
        addTerm(bool, EsConstants.Field.FREE, query.getFree());
        addTerm(bool, EsConstants.Field.TYPE, query.getType());
        addTerm(bool, EsConstants.Field.GOAL_TYPE, query.getGoalType());
        addTerm(bool, EsConstants.Field.TRAINING_LEVEL, query.getTrainingLevel());

        if (query.getMinPrice() != null || query.getMaxPrice() != null) {
            var range = QueryBuilders.rangeQuery(EsConstants.Field.PRICE);
            if (query.getMinPrice() != null) {
                range.gte(query.getMinPrice());
            }
            if (query.getMaxPrice() != null) {
                range.lte(query.getMaxPrice());
            }
            bool.filter(range);
        }
        if (StringUtils.hasText(query.getBeginTime()) || StringUtils.hasText(query.getEndTime())) {
            var range = QueryBuilders.rangeQuery(EsConstants.Field.PUBLISH_TIME);
            if (StringUtils.hasText(query.getBeginTime())) {
                range.gte(query.getBeginTime());
            }
            if (StringUtils.hasText(query.getEndTime())) {
                range.lte(query.getEndTime());
            }
            bool.filter(range);
        }
        return bool;
    }

    private static void addTerm(BoolQueryBuilder bool, String field, Object value) {
        if (value != null) {
            bool.filter(QueryBuilders.termQuery(field, value));
        }
    }

    private static HighlightBuilder buildHighlight() {
        return new HighlightBuilder()
                .field(EsConstants.Field.NAME)
                .preTags("<em>")
                .postTags("</em>")
                .requireFieldMatch(false);
    }

    private static void applySort(SearchSourceBuilder source, CourseSearchQuery query) {
        boolean asc = Boolean.TRUE.equals(query.getRankAsc());
        String rankBy = query.getRankBy();
        if (EsConstants.Field.PRICE.equals(rankBy)) {
            source.sort(EsConstants.Field.PRICE, asc ? SortOrder.ASC : SortOrder.DESC);
        } else if (EsConstants.Field.PUBLISH_TIME.equals(rankBy)) {
            source.sort(EsConstants.Field.PUBLISH_TIME, asc ? SortOrder.ASC : SortOrder.DESC);
        } else if (EsConstants.Field.SOLD.equals(rankBy)) {
            source.sort(EsConstants.Field.SOLD, asc ? SortOrder.ASC : SortOrder.DESC);
        } else {
            // 综合：默认按 _score（相关性），同分时报名数多的靠前
            source.sort(EsConstants.Field.SOLD, SortOrder.DESC);
        }
    }

    private List<CourseDoc> simpleQuery(org.elasticsearch.index.query.QueryBuilder query,
                                        String sortField, int size) {
        if (!indexReady()) {
            return List.of();
        }
        try {
            SearchSourceBuilder source = new SearchSourceBuilder().query(query).size(size);
            if (sortField != null) {
                source.sort(sortField, SortOrder.DESC);
            }
            SearchResponse response = client.search(new SearchRequest(indexName).source(source), RequestOptions.DEFAULT);
            return toDocs(response);
        } catch (IOException | ElasticsearchException e) {
            if (e.getMessage() != null && e.getMessage().contains("index_not_found_exception")) {
                resetIndexFlag();
                return List.of();
            }
            log.error("ES 查询失败：{}", e.getMessage());
            throw new BizIllegalException(SearchErrorInfo.Msg.INDEX_QUERY_FAILED);
        }
    }

    private SearchPage toPage(SearchResponse response) {
        List<CourseDoc> docs = new ArrayList<>();
        Map<Long, String> highlight = new HashMap<>();
        for (SearchHit hit : response.getHits().getHits()) {
            CourseDoc doc = parseDoc(hit);
            if (doc == null) {
                continue;
            }
            docs.add(doc);
            HighlightField nameHl = hit.getHighlightFields().get(EsConstants.Field.NAME);
            if (nameHl != null && nameHl.getFragments().length > 0) {
                highlight.put(doc.getId(), nameHl.getFragments()[0].string());
            }
        }
        return new SearchPage(response.getHits().getTotalHits().value, docs, highlight);
    }

    private List<CourseDoc> toDocs(SearchResponse response) {
        List<CourseDoc> docs = new ArrayList<>();
        for (SearchHit hit : response.getHits().getHits()) {
            CourseDoc doc = parseDoc(hit);
            if (doc != null) {
                docs.add(doc);
            }
        }
        return docs;
    }

    private CourseDoc parseDoc(SearchHit hit) {
        try {
            return esObjectMapper.readValue(hit.getSourceAsString(), CourseDoc.class);
        } catch (IOException e) {
            log.error("解析索引文档失败：id={}，原因：{}", hit.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 索引是否可用。
     *
     * <p>正常路径下只是读一个 volatile 标记，<b>不会每次查询都往 ES 发 exists 请求</b>——
     * 那会让每次检索都多一次网络往返。</p>
     *
     * <p>标记在索引被报"不存在"时会清零（见 {@code resetIndexFlag()}），
     * 于是下一次查询会重新补建索引，自动从"索引被人删了"里恢复。</p>
     */
    private boolean indexReady() {
        if (indexEnsured) {
            return true;
        }
        try {
            ensureIndex();
            return true;
        } catch (Exception e) {
            log.warn("索引不可用：{}", e.getMessage());
            return false;
        }
    }

    /** 索引不存在时现场补建（幂等），并用标记避免重复检查 */
    private void ensureIndex() {
        if (indexEnsured) {
            return;
        }
        synchronized (this) {
            if (indexEnsured) {
                return;
            }
            try {
                indexInitializer.createIndexIfAbsent(indexName);
                indexEnsured = true;
            } catch (Exception e) {
                throw new BizIllegalException(SearchErrorInfo.Msg.INDEX_QUERY_FAILED);
            }
        }
    }

    /** 索引被删时清零标记，让下次请求重新补建 */
    private void resetIndexFlag() {
        indexEnsured = false;
    }
}
