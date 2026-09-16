package com.fitmind.search.config;

import com.fitmind.search.constants.EsConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.settings.Settings;
// ⚠️ ES 7.17 起 XContentType 从 org.elasticsearch.common.xcontent 挪到了 org.elasticsearch.xcontent
import org.elasticsearch.xcontent.XContentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 索引初始化：应用启动时幂等创建课程索引。
 *
 * <p><b>为什么不由人手工建</b>：常见实现就没有建索引的代码，
 * 索引靠手工敲 mapping JSON。那样换一台机器起来就搜不了，而报错还很不直观。
 * 这里和 M2-1 的 MinIO bucket 初始化是同一个思路——<b>让环境可重建</b>。</p>
 *
 * <p>mapping 必须手写（没用 Spring Data 的注解），所以字段名要和
 * {@code EsConstants.Field}、{@code CourseDoc} 三方对齐；这也是为什么
 * 字段名全都收在常量类里，不散落在代码各处。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexInitializer implements ApplicationRunner {

    /**
     * 课程索引的 mapping。
     *
     * <p>要点：</p>
     * <ul>
     *   <li>所有中文文本字段都用 IK 分词器：索引时 {@code ik_max_word}（切得细，召回全），
     *       检索时 {@code ik_smart}（切得粗，精度高）——这是 IK 的标准用法</li>
     *   <li>{@code coverUrl} 是 keyword 且 {@code index: false}：只做展示，不参与检索，省空间</li>
     *   <li>单节点环境副本必须为 0，否则集群健康度一直是 yellow</li>
     * </ul>
     */
    private static final String COURSE_MAPPING = """
            {
              "properties": {
                "id":             { "type": "long" },
                "name":           { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
                "categoryIdLv1":  { "type": "long" },
                "categoryIdLv2":  { "type": "long" },
                "categoryIdLv3":  { "type": "long" },
                "categoryPath":   { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
                "free":           { "type": "boolean" },
                "type":           { "type": "integer" },
                "price":          { "type": "integer" },
                "score":          { "type": "integer" },
                "sold":           { "type": "integer" },
                "teacherIds":     { "type": "long" },
                "teacherNames":   { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
                "sections":       { "type": "integer" },
                "coverUrl":       { "type": "keyword", "index": false },
                "publishTime":    { "type": "date" },
                "updateTime":     { "type": "date" },
                "goalType":       { "type": "integer" },
                "trainingLevel":  { "type": "integer" },
                "equipment":      { "type": "keyword" },
                "totalDays":      { "type": "integer" },
                "mediaDuration":  { "type": "integer" },
                "trainingDays":   { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
                "actionNames":    { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" }
              }
            }
            """;

    private final RestHighLevelClient client;

    @Value("${elasticsearch.index:fitmind_course}")
    private String indexName;

    @Override
    public void run(ApplicationArguments args) {
        try {
            createIndexIfAbsent(indexName);
        } catch (Exception e) {
            // 不阻断启动：ES 暂时起不来时，服务仍然应该能注册上去，
            // 否则排障时连健康检查都没法做。但日志要足够响。
            log.error("❌ 课程索引初始化失败，检索功能将不可用。请确认 ES(http://{}:{}) 已就绪且装了 IK 分词器，"
                            + "之后重启本服务或调用内部接口重建索引。错误：{}",
                    hostAndPort(), indexName, e.getMessage());
        }
    }

    /**
     * 幂等创建索引。
     *
     * @return true-本次新建 / false-已存在
     */
    public boolean createIndexIfAbsent(String index) throws Exception {
        if (client.indices().exists(new GetIndexRequest(index), RequestOptions.DEFAULT)) {
            log.info("索引 {} 已存在，跳过创建", index);
            return false;
        }
        CreateIndexRequest request = new CreateIndexRequest(index);
        request.settings(Settings.builder()
                .put("index.number_of_shards", EsConstants.SHARDS)
                .put("index.number_of_replicas", EsConstants.REPLICAS));
        request.mapping(COURSE_MAPPING, XContentType.JSON);

        var response = client.indices().create(request, RequestOptions.DEFAULT);
        if (response.isAcknowledged()) {
            log.info("✅ 索引 {} 创建成功（分片={}，副本={}）", index, EsConstants.SHARDS, EsConstants.REPLICAS);
        }
        return true;
    }

    private String hostAndPort() {
        // 仅用于日志，避免把配置再注入一次
        return String.valueOf(client.getLowLevelClient().getNodes().get(0).getHost());
    }
}
