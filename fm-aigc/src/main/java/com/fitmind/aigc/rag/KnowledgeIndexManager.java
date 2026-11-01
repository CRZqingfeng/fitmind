package com.fitmind.aigc.rag;

import com.fitmind.aigc.config.AigcProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识库索引管理 —— 建索引、重建、健康检查。
 *
 * <p>它把"语料 → 分块 → 向量化 → 入 ES"这条流水线固定在一处，
 * 于是有两个消费方：<b>启动时的自动引导</b>（索引不存在就建）和
 * <b>管理端的手动重建</b>（改了语料 / 换了 embedding 模型之后）。</p>
 *
 * <p>★ <b>重建为什么是"先删索引再建"，而不是"删掉所有文档再写"</b>：
 * ① 维度变了的话，旧索引的 mapping 是**改不了**的（dense_vector 的 dims 不可变），
 *    必须删掉重建，所以只能这么做；
 * ② 顺带把 mapping 也刷新了（分块策略变了、加了新元数据字段时同样需要）。</p>
 *
 * <p>★ 启动引导刻意做成"尽力而为"：知识库没建起来时，服务<b>照样启动</b>，
 * 只是健康标记为不健康、检索返回空（对话退化成普通问答）。
 * 理由和 API Key 兜底一致 —— 一个能力不可用，不该升级成整个服务不可用。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeIndexManager {

    private final AigcProperties props;
    private final ElasticsearchVectorStore vectorStore;

    /** 上一次构建的结果，供健康检查与验收读取 */
    private volatile KnowledgeStatus lastStatus;

    /** 启动引导
     * 三道闸门：
     * <p>索引不存在 → 首次构建</p>
     * <p>维度不一致 → 报警但不阻塞启动</p>
     * <p>初始化失败 → 服务继续启动，检索返回空</p>
     * */
    @PostConstruct
    public void bootstrap() {
        AigcProperties.Rag rag = props.getRag();
        log.info("★ 知识库配置：索引={}，embedding={}（{} 维），检索 topK={}，阈值={}，混合={}",
                rag.indexName(), rag.getProvider(), rag.getDimensions(),
                rag.getTopK(), rag.getSimilarityThreshold(), rag.isHybrid());
        try {
            if (!vectorStore.indexExists()) {
                log.warn("★★ 知识库索引不存在，开始首次构建：{}", rag.indexName());
                rebuild();
                return;
            }
            Integer mapped = vectorStore.mappedDimensions();
            if (mapped != null && mapped != rag.getDimensions()) {
                // ★★ 这就是"换了 embedding 模型但索引没重建" —— 最隐蔽的一类故障：
                //    索引在、数据在、查询不报错，但两批向量不在同一个空间里，
                //    相关性全错。表现是"AI 答非所问/什么都答不出来"，
                //    而排查方向很容易跑到模型或提示词上去。
                log.error("★★★ 知识库维度不一致：索引里是 {} 维，当前配置是 {} 维。"
                                + "\\n    这通常意味着换了 embedding 模型但没重建索引 —— 检索结果会完全不相关（而且不报错）。"
                                + "\\n    修法：调用管理端重建接口 POST /ais/admin/knowledge/rebuild",
                        mapped, rag.getDimensions());
                lastStatus = new KnowledgeStatus(rag.indexName(), true, false, vectorStore.count(),
                        mapped, rag.getDimensions(), 0, 0,
                        "维度不一致：索引 " + mapped + " 维 / 配置 " + rag.getDimensions() + " 维，请重建");
                return;
            }
            long count = vectorStore.count();
            lastStatus = new KnowledgeStatus(rag.indexName(), true, count > 0, count,
                    mapped, rag.getDimensions(), 0, (int) count, "索引已就绪");
            log.info("★ 知识库索引已就绪：{}，{} 条向量", rag.indexName(), count);
        } catch (Exception e) {
            // 不让它把服务带崩：对话本身还能用，只是没有知识依据
            log.error("★★ 知识库初始化失败（服务继续启动，检索将返回空）：{}", e.getMessage(), e);
            lastStatus = new KnowledgeStatus(rag.indexName(), false, false, -1,
                    null, rag.getDimensions(), 0, 0, "初始化失败：" + e.getMessage());
        }
    }

    /**
     * 全量重建：删索引 → 建索引 → 语料分块 → 向量化写入 → 刷新。
     *
     * @return 构建结果（语料篇数 / 分块数 / 写入条数）
     */
    public synchronized KnowledgeStatus rebuild() {
        long start = System.currentTimeMillis();
        AigcProperties.Rag rag = props.getRag();

        // ①加载语料入口
        MarkdownCorpusLoader loader = new MarkdownCorpusLoader(rag.getCorpusLocation());
        List<MarkdownCorpusLoader.CorpusDocument> corpus = loader.load();
        // ②语义分块入口
        MarkdownChunker chunker = new MarkdownChunker(rag.getChunkSize(), rag.getChunkOverlap());
        List<Document> chunks = new ArrayList<>();
        for (MarkdownCorpusLoader.CorpusDocument doc : corpus) {
            chunks.addAll(chunker.split(doc));
        }
        if (chunks.isEmpty()) {
            throw new IllegalStateException("分块结果为空 —— 语料有内容但一块都没切出来，检查 chunk-size 配置");
        }

        // 索引重建入口
        if (vectorStore.indexExists()) {
            vectorStore.dropIndex();
        }
        vectorStore.createIndex();
        // 分批写入：一次几百条对 ES 来说没问题，但分批能让日志和失败定位更清楚，
        // 也避免单个 bulk 请求体过大
        int batch = 50;
        for (int i = 0; i < chunks.size(); i += batch) {
            // ③向量化核心
            vectorStore.add(chunks.subList(i, Math.min(chunks.size(), i + batch)));
        }
        vectorStore.refresh();

        long cost = System.currentTimeMillis() - start;
        Integer mapped = vectorStore.mappedDimensions();
        lastStatus = new KnowledgeStatus(rag.indexName(), true, true, chunks.size(),
                mapped, rag.getDimensions(), corpus.size(), chunks.size(),
                "重建完成，耗时 " + cost + "ms");
        log.info("★★ 知识库重建完成：{} 篇语料 → {} 块 → 索引 {}（{} 维），耗时 {}ms",
                corpus.size(), chunks.size(), rag.indexName(), rag.getDimensions(), cost);
        return lastStatus;
    }

    /** 当前状态（不触发任何写操作，可以随时查） */
    public KnowledgeStatus status() {
        AigcProperties.Rag rag = props.getRag();
        KnowledgeStatus cached = lastStatus;
        if (cached != null) {
            return cached;
        }
        try {
            long count = vectorStore.count();
            return new KnowledgeStatus(rag.indexName(), vectorStore.indexExists(), count > 0, count,
                    null, rag.getDimensions(), 0, (int) count, "未初始化");
        } catch (Exception e) {
            return new KnowledgeStatus(rag.indexName(), false, false, -1,
                    null, rag.getDimensions(), 0, 0, "查询失败：" + e.getMessage());
        }
    }

    /** 语料篇数 / 块大小等静态信息（管理端状态接口用，不必重建就能看到） */
    public Map<String, Object> corpusInfo() {
        AigcProperties.Rag rag = props.getRag();
        return Map.of(
                "corpusLocation", rag.getCorpusLocation(),
                "chunkSize", rag.getChunkSize(),
                "chunkOverlap", rag.getChunkOverlap(),
                "embeddingModel", rag.getModel(),
                "dimensions", rag.getDimensions(),
                "indexName", rag.indexName(),
                "topK", rag.getTopK(),
                "similarityThreshold", rag.getSimilarityThreshold(),
                "hybrid", rag.isHybrid());
    }

    /**
     * 知识库状态。
     *
     * @param indexName     索引名
     * @param indexExists   索引是否存在
     * @param healthy       是否可用于检索
     * @param vectorCount   索引里的向量条数
     * @param mappedDims    索引 mapping 里记录的维度（null = 读不出来）
     * @param configuredDims 当前配置的维度
     * @param corpusDocs    上次重建的语料篇数
     * @param chunkCount    上次重建的分块数
     * @param message       人话描述（排查时第一眼要看的东西）
     */
    public record KnowledgeStatus(String indexName, boolean indexExists, boolean healthy, long vectorCount,
                                  Integer mappedDims, int configuredDims, int corpusDocs, int chunkCount,
                                  String message) {
    }
}
