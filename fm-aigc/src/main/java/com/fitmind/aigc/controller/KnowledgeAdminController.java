package com.fitmind.aigc.controller;

import com.fitmind.aigc.rag.KnowledgeIndexManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库管理端接口。
 *
 * <p>全部走 {@code /admin/**} 前缀，由 {@link com.fitmind.aigc.config.AdminAuthInterceptor}
 * 校验管理员身份（网关侧完整路径是 {@code /ais/admin/knowledge/**}）。</p>
 *
 * <p>★ 为什么重建必须是个"手动触发的管理端接口"而不是定时任务：
 * 重建会<b>先删索引再建</b>，期间检索不可用（对话退化为无依据回答），
 * 而且会消耗几百次 embedding 调用（真金白银）。
 * 这两个特征决定了它应该由人决定"什么时候做"，
 * 而不是每天凌晨自作主张跑一次 —— <b>有副作用的批处理，能手动就别自动。</b></p>
 */
@Slf4j
@RestController
@RequestMapping("/admin/knowledge")
@RequiredArgsConstructor
@Tag(name = "AI 知识库管理端")
public class KnowledgeAdminController {

    private final KnowledgeIndexManager indexManager;
    private final com.fitmind.aigc.config.AigcProperties props;

    /**
     * 知识库状态。
     *
     * <p>★ 它回答的是排查时最先要问的三个问题：
     * ① 索存在吗？② 有多少条向量？③ <b>索引里的维度和当前配置的维度一致吗？</b>
     * 第三个是最隐蔽的一类故障 —— 换了 embedding 模型但没重建索引时，
     * 索引在、数据在、查询也不报错，只是<b>相关性全错</b>。</p>
     */
    @Operation(summary = "知识库状态（索引/条数/维度一致性）")
    @GetMapping("/status")
    public Map<String, Object> status() {
        KnowledgeIndexManager.KnowledgeStatus s = indexManager.status();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("indexName", s.indexName());
        result.put("indexExists", s.indexExists());
        result.put("healthy", s.healthy());
        result.put("vectorCount", s.vectorCount());
        result.put("mappedDimensions", s.mappedDims());
        result.put("configuredDimensions", s.configuredDims());
        result.put("corpusDocs", s.corpusDocs());
        result.put("chunkCount", s.chunkCount());
        result.put("message", s.message());
        result.putAll(indexManager.corpusInfo());
        return result;
    }

    /**
     * 故障注入：让检索强制失败。
     *
     * <p>★★ 存在的唯一理由是<b>把降级路径也测一遍</b>。
     * "知识库挂了会怎样"靠读代码看不出来 —— 读得出"会 catch"，
     * 读不出"catch 之后用户看到的是拒答还是正常回答"。
     * 而这一次区分（{@code failed} vs {@code empty}）恰恰是 M6-6 修的语义问题。
     * 这也是生产上"混沌工程"的最小形态：<b>故障路径不演练，第一次演练就是线上。</b></p>
     */
    @Operation(summary = "故障注入：开关检索失败（验证降级路径，重启恢复）")
    @PostMapping("/fail-mode")
    public Map<String, Object> failMode(@RequestParam(defaultValue = "true") boolean enabled) {
        props.getRag().setSimulateFailure(enabled);
        log.warn("★ 知识库故障注入已{}：后续检索将{}", enabled ? "开启" : "关闭", enabled ? "强制失败" : "恢复正常");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("simulateFailure", props.getRag().isSimulateFailure());
        result.put("note", "只影响检索；重启后恢复配置值");
        return result;
    }

    /**
     * 重建知识库索引（全量）。
     *
     * <p>⚠️ 会先删索引再建，期间检索不可用。语料几十篇时耗时通常几秒到几十秒
     * （取决于 embedding 的响应速度）。</p>
     */
    @Operation(summary = "重建知识库索引（删索引→建索引→语料分块→向量化写入）")
    @PostMapping("/rebuild")
    public Map<String, Object> rebuild() {
        long start = System.currentTimeMillis();
        KnowledgeIndexManager.KnowledgeStatus s = indexManager.rebuild();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("indexName", s.indexName());
        result.put("healthy", s.healthy());
        result.put("corpusDocs", s.corpusDocs());
        result.put("chunkCount", s.chunkCount());
        result.put("vectorCount", s.vectorCount());
        result.put("configuredDimensions", s.configuredDims());
        result.put("costMs", System.currentTimeMillis() - start);
        result.put("message", s.message());
        return result;
    }
}
