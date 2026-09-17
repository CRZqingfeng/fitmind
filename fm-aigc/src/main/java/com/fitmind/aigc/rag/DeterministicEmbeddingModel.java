package com.fitmind.aigc.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置确定性向量模型 —— RAG 链路的"离线替身"。
 *
 * <p><b>它为什么存在</b>（和 {@code MockChatModel} 是同一个理由）：
 * 真实 embedding 依赖外部 API —— 要网络、要 key、要额度、会限流。
 * 如果链路里每一环都依赖它，那么"改一行分块参数，看看链路有没有断"这件事
 * 就必须真调 API 才能完成，慢、要钱、还会随机失败。
 * 有了确定性替身，这条链路可以在完全没有网络的环境里被自动验收。</p>
 *
 * <p><b>它不是什么</b>：它<b>没有语义能力</b>。
 * 实现方式是"字符 2-gram 哈希投影到固定维度 + L2 归一化"——
 * 只保证"同样的输入得到同样的向量、有共同字符的文本相似度更高"。
 * 所以它只能验证<b>链路是否连通</b>（分块 → 向量化 → 入索引 → 检索 → 进 prompt），
 * <b>不能用来评估检索质量</b>。命中率评估必须在真实向量下跑。</p>
 *
 * <p>★ 与真实模型的向量<b>互不兼容</b>。项目用"索引名自动带上模型标识"来防这件事
 * （见 {@code AigcProperties.Rag#indexName()}）—— 两者落在不同索引里，
 * 不可能出现"用了 A 模型的向量去查 B 模型的索引"这种静默降级。</p>
 */
public class DeterministicEmbeddingModel implements EmbeddingModel {

    /** 每个字符对向量的贡献：单字 + 双字组合，让"词序不同但用词相近"也能有相似度 */
    private static final int NGRAM = 2;

    private final int dimensions;

    public DeterministicEmbeddingModel(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<float[]> vectors = new ArrayList<>();
        for (String text : request.getInstructions()) {
            vectors.add(embedText(text));
        }
        List<org.springframework.ai.embedding.Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < vectors.size(); i++) {
            embeddings.add(new org.springframework.ai.embedding.Embedding(vectors.get(i), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embedText(document.getText());
    }

    @Override
    public int dimensions() {
        // ★ 上报的维度必须是**真实使用的维度**：索引 schema 由它推导，
        //   报错了就会出现"索引建了 1024 维、写进去 512 维"这种写不进去还没提示的问题
        return dimensions;
    }

    private float[] embedText(String text) {
        float[] vector = new float[dimensions];
        if (text == null || text.isEmpty()) {
            return vector;
        }
        String normalized = text.replaceAll("\\s+", " ").trim().toLowerCase();
        for (int i = 0; i < normalized.length(); i++) {
            // 单字：覆盖"只有一个字也对得上"的情况
            add(vector, normalized.substring(i, i + 1), 1.0f);
            if (i + NGRAM <= normalized.length()) {
                add(vector, normalized.substring(i, i + NGRAM), 0.6f);
            }
        }
        return l2Normalize(vector);
    }

    /**
     * 把一段文本"投影"到向量的某几维上。
     *
     * <p>用多个哈希位叠加而不是取模到单独一维：单个位置衰减太快，
     * 只要文本有一个字不同，相似度就会掉得很厉害，那样连"链路是否连通"都测不准。</p>
     */
    private void add(float[] vector, String key, float weight) {
        int h = key.hashCode();
        // 三个独立的混合位（乘不同质数），让相近的 key 不会总是撞在同一维
        vector[Math.floorMod(h, dimensions)] += weight;
        vector[Math.floorMod(h * 31 + 7, dimensions)] += weight * 0.5f;
        vector[Math.floorMod(h * 131 + 17, dimensions)] += weight * 0.25f;
    }

    private float[] l2Normalize(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        if (sum == 0) {
            return vector;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
        return vector;
    }
}
