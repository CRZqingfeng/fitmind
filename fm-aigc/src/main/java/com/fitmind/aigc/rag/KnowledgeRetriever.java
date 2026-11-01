package com.fitmind.aigc.rag;

import cn.hutool.core.util.StrUtil;
import com.fitmind.aigc.agent.AgentContext;
import com.fitmind.aigc.config.AigcProperties;
import com.fitmind.aigc.constants.AigcConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识检索器 —— RAG 的"取"这一半。
 *
 * <p><b>★ 它和常见实现的差别，一句话：常见实现没有"取"，它把整库都塞进去了。</b>
 * 那边的写法是 {@code SearchRequest.query("")} + {@code topK(999)}：
 * 空查询在向量空间里等于"随机取"，所谓检索退化成"全量拷贝"。
 * 代价是每轮都要为整个知识库付 prompt token、真正相关的依据被淹在噪声里、
 * 语料一多 prompt 直接超长。</p>
 *
 * <p>这里做四件常见实现没做的事：
 * <ol>
 *   <li><b>真实的查询</b>：用用户的问题去检索，而不是空串；</li>
 *   <li><b>相似度阈值</b>：低于阈值的结果<b>不送进 prompt</b>（宁可拒答，不要误导）——
 *       没有阈值时，知识库里没有相关内容，模型仍会拿到 4 条"最不相关的内容"，
 *       然后一本正经地基于它们编答案，比直接说"不知道"危险得多；</li>
 *   <li><b>元数据过滤</b>：按用户训练目标过滤（减脂用户拿不到增肌饮食文档）——
 *       这比调 topK 有效得多，因为 topK 只能控制"给几条"，控制不了"给的对不对"；</li>
 *   <li><b>关键词 + 向量混合</b>：两路召回用 RRF 融合。纯向量在精确术语
 *       （"臀桥"、"RPE 8"）和数字上会漏，而 ES 本身就是全文检索引擎，这条路是白送的。</li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeRetriever {

    private final ElasticsearchVectorStore vectorStore;
    private final AigcProperties props;
    private final KnowledgeIndexManager indexManager;

    /**
     * 执行一次检索。
     *
     * @param question 用户的问题（真实查询）
     * @param options  检索参数覆盖（目标 / topK / 阈值），字段为 null 时用配置默认值
     * @param trace    检索轨迹（就地填充，供溯源与落库）
     * @return 要注入 prompt 的文档（可能为空 —— 空就代表"该拒答了"）
     */
    public List<Document> retrieve(String question, AgentContext.RetrievalOptions options, RagTrace trace) {
        AigcProperties.Rag rag = props.getRag();
        // 1. 参数解析（请求级覆盖 + 配置级兜底）
        //设计要点：请求级覆盖 + 配置级兜底。
        // 请求级覆盖：null 就用配置默认值（同一个三元表达式写了三遍，
        // 因为"覆盖"这件事必须是逐字段的 —— 只传 topK 时阈值仍要取默认值）
        String goal = options == null ? null : options.goal();
        int topK = options == null || options.topK() == null ? rag.getTopK() : Math.max(1, options.topK());
        double threshold = options == null || options.threshold() == null
                ? rag.getSimilarityThreshold() : options.threshold();
        // 2. 前置校验
        // 向量一路的召回条数跟着 topK 走：覆盖了 topK 却还按配置的 vectorTopK 召回，
        // 会让"topK=10"这种测试拿到少于 10 条的结果，看起来像检索没生效
        int vectorTopK = Math.max(topK, rag.getVectorTopK());
        int keywordTopK = Math.max(topK, rag.getKeywordTopK());
        boolean hybrid = options == null || options.hybrid() == null ? rag.isHybrid() : options.hybrid();
        if (StrUtil.isBlank(question)) {
            trace.skip("提问为空，跳过检索");
            return List.of();
        }
        if (rag.isSimulateFailure()) {
            // ★ 故障注入：让"知识库不可用"这条降级路径能被真的跑一遍。
            //   注意它走的是 markFailed（不是"为空"）—— 模型应当照常回答并说明没能查证，
            //   而不是拒答。两者的区别见 AigcConstants.RAG_UNAVAILABLE_NOTICE。
            trace.markFailed("故障注入：模拟知识库不可用");
            log.warn("★ 故障注入已开启：本轮检索强制失败（用于验证降级路径）");
            return List.of();
        }
        KnowledgeIndexManager.KnowledgeStatus status = indexManager.status();
        if (!status.healthy()) {
            // ★ 检索不可用不是"报错"，而是"这一轮没有依据"：
            //   对话要继续，只是模型不能声称自己有依据
            trace.markFailed("知识库不可用：" + status.message());
            log.warn("知识库不可用，本轮退化为无依据回答：{}", status.message());
            return List.of();
        }

        long start = System.currentTimeMillis();
        try {
            // 3. ★★★构建检索请求
            Filter.Expression filter = buildFilter(goal);
            SearchRequest request = SearchRequest.builder()
                    .query(question)
                    // 先多召回一些，融合后再裁到 topK ——
                    // 否则"向量 4 条 + 关键词 6 条"融合完可能只剩 2 条进 prompt
                    .topK(hybrid ? vectorTopK : topK)
                    .similarityThreshold(threshold)
                    .filterExpression(filter)
                    .build();
            //4. 双路召回
            List<Document> vectorHits = vectorStore.similaritySearch(request);

            List<Document> keywordHits = List.of();
            if (hybrid) {
                keywordHits = vectorStore.keywordSearch(question, keywordTopK, filter);
            }
            // 5. RRF 融合
            List<Document> fused = hybrid
                    ? ElasticsearchVectorStore.fuseByRrf(List.of(vectorHits, keywordHits), topK)
                    : vectorHits;

            //6.成本护栏 + 轨迹记录
            List<Document> injected = truncate(fused, rag.getMaxContextChars());
            long cost = System.currentTimeMillis() - start;
            trace.record(toSources(injected), vectorHits.size() + keywordHits.size(), injected.size(),
                    injected.stream().mapToInt(d -> d.getText().length()).sum(), cost, hybrid);

            log.info("知识检索[{}]：问题=\"{}\"{}，topK={} 阈值={}，候选 {} 条（向量 {} / 关键词 {}）→ 注入 {} 条，{} 字，耗时 {}ms",
                    hybrid ? "混合" : "向量", StrUtil.brief(question, 24),
                    StrUtil.isBlank(goal) ? "" : "，目标过滤=" + goal,
                    topK, threshold,
                    vectorHits.size() + keywordHits.size(), vectorHits.size(), keywordHits.size(),
                    injected.size(), trace.getContextChars(), cost);
            return injected;
        } catch (Exception e) {
            //★降级
            // ★ 检索失败降级为"无知识的普通对话"，而不是让整轮对话 500。
            //   一个可选能力（检索）坏了，不该把主能力（对话）也带下去。
            trace.markFailed("检索失败：" + e.getMessage());
            log.error("知识检索失败（本轮退化为无依据回答）：{}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 构造元数据过滤：目标匹配 <b>或</b> 通用文档。
     *
     * <p>★ 为什么要带上 {@code general}：像"训练原则"、"恢复与睡眠"这类文档
     * 对所有训练目标都成立，语料里把它们标成 {@code goal: [general]}。
     * 如果只按用户目标精确过滤，这类"通用但很有用"的文档会被一刀切掉 ——
     * 元数据过滤是一把双刃剑，用过头会让召回率掉得比准确率涨得快。</p>
     */
    private Filter.Expression buildFilter(String goal) {
        if (StrUtil.isBlank(goal)) {
            return null;
        }
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        return b.or(
                b.eq(AigcConstants.META_GOAL, goal.trim()),
                b.eq(AigcConstants.META_GOAL, "general")
        ).build();
    }

    /**
     * 按字符上限截断注入内容。
     *
     * <p>★ 这是<b>成本护栏</b>，防的不是正常情况而是异常情况：
     * 分块参数被人改大、语料突然多了几篇超长文档 —— 那些时候
     * "进 prompt 的内容"会悄悄膨胀，而症状是"某天开始 AI 回答变慢变贵"。
     * 有个硬上限，最坏情况就是"少了两条依据"，而不是"账单翻十倍"。</p>
     */
    private List<Document> truncate(List<Document> docs, int maxChars) {
        List<Document> result = new ArrayList<>();
        int used = 0;
        for (Document doc : docs) {
            int len = doc.getText() == null ? 0 : doc.getText().length();
            if (used + len > maxChars && !result.isEmpty()) {
                break;
            }
            result.add(doc);
            used += len;
        }
        return result;
    }

    private List<RagTrace.Source> toSources(List<Document> docs) {
        List<RagTrace.Source> sources = new ArrayList<>();
        for (Document doc : docs) {
            var meta = doc.getMetadata();
            String text = doc.getText() == null ? "" : doc.getText();
            sources.add(new RagTrace.Source(
                    str(meta.get(AigcConstants.META_DOC_ID)),
                    str(meta.get(AigcConstants.META_TITLE)),
                    str(meta.get(AigcConstants.META_SECTION)),
                    doc.getScore(),
                    StrUtil.brief(text.replaceAll("\\s+", " "), 60)));
        }
        return sources;
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
