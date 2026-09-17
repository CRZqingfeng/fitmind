package com.fitmind.aigc.config;

import com.fitmind.aigc.constants.AigcConstants;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 模块配置。
 *
 * <p>★ 命名空间用 {@code fm.ai} 而不是复用什么已有的前缀：本模块的配置项
 * 全部是"AI 行为"相关的（通道、分片、超时、检索参数），
 * 和业务配置（端口、数据源）分开，改起来边界清楚。</p>
 */
@Data
@ConfigurationProperties(prefix = "fm.ai")
public class AigcProperties {

    private Chat chat = new Chat();
    private Mock mock = new Mock();
    private Prompt prompt = new Prompt();
    private Rag rag = new Rag();
    private Tool tool = new Tool();

    @Data
    public static class Chat {

        /**
         * 使用的模型通道：{@code deepseek} 或 {@code mock}。
         *
         * <p>★ 这是本模块最重要的一行配置。默认 {@code deepseek}，
         * 但**密钥缺失时会自动退回 mock**（见 {@code AiModelConfig}）——
         * 因为我们不希望"没配 key"的表现是启动失败或调用报错，
         * 而是"能跑，但走的是确定性替身"。</p>
         */
        private String provider = AigcConstants.PROVIDER_DEEPSEEK;

        /**
         * 单轮模型调用超时（毫秒）。
         *
         * <p>★ 必须有上限：模型服务抖动时可能几十秒不返回，
         * 而 SSE 连接一直挂着会占住 Tomcat 线程池。
         * 超时后返回可读的降级文案（不是 500），见 {@code AigcConstants.FALLBACK_MODEL_BUSY}。</p>
         */
        private int timeoutMs = 60_000;

        /**
         * 默认编排策略：{@code ROUTE} / {@code ENHANCE} / {@code SIMPLE}。
         *
         * <p>它是"部署级默认值"，请求可以临时覆盖（见 {@code ChatRequestDTO.chatType}）——
         * 覆盖能力主要给演示与验收用：同一个提问在三种策略下各跑一遍，
         * 就能横向对比延迟、模型调用次数与回答质量。</p>
         */
        private String type = "ROUTE";

        /**
         * 意图路由单独的超时（毫秒）。
         *
         * <p>★ 必须比主回答的超时短得多：路由的任务只是"输出一个词"，
         * 但它处在链路最前面 —— 用主超时（60s）的话，
         * 模型侧一抖动用户就要<b>干等 60 秒才看到第一个字</b>。
         * 给它 10 秒足够了，超了就兜底到知识问答（用户几乎无感）。</p>
         */
        private int routeTimeoutMs = 10_000;
    }

    @Data
    public static class Mock {

        /**
         * 每个分片的字符数。
         *
         * <p>★ 为什么 Mock 要"真的分片"而不是一次返回完整字符串：
         * 分片是**被验证对象**的一部分。SSE 的拼接逻辑、前端打字机、
         * 打断时的"已生成部分"落库，都只在"有多个分片"时才被真正执行到。
         * 一次返回完整字符串的话，这些代码在验收里等于没跑。</p>
         */
        private int chunkSize = 4;

        /** 分片间隔（毫秒）。太小测不出打字机效果，太大拖慢验收脚本 */
        private int chunkDelayMs = 30;

        /**
         * 是否把 prompt 快照写进 Redis（供验收断言）。
         *
         * <p>默认开：这是"能自动验收 RAG"的关键（断言"检索到的文档确实进了 prompt"）。
         * 关掉它的场景只有一个 —— 压测时不想让 Redis 承担这个写入。</p>
         */
        private boolean snapshotPrompt = true;
    }

    /**
     * 提示词（Prompt）配置。
     *
     * <p>提示词的三级来源，<b>从高到低</b>：
     * ① Nacos 配置（可热更新，不用重启服务）
     * ② classpath 下的 {@code prompts/{key}.txt}（跟代码一起发版）
     * ③ {@code PromptManager} 里的代码兜底常量
     * </p>
     *
     * <p>★ 为什么要有第 ②③ 级 —— 这是对常见实现的一处实质修正：
     * 它只在启动时从 Nacos 读一次，读失败只打一行 error 日志，
     * {@code AtomicReference} 里保持 null，于是 {@code systemMessage()} 返回空串 ——
     * <b>模型收到的 system 提示是空的</b>，回答风格、安全边界、输出格式全部失效。
     * 这个故障的表现是"AI 突然变得不受控"，而日志里只有一行容易被忽略的 error，
     * 排查方向还容易跑偏到模型或网络上去。</p>
     *
     * <p>三级兜底之后，"提示词为空"这件事在结构上就不可能发生。</p>
     */
    @Data
    public static class Prompt {

        private Nacos nacos = new Nacos();

        @Data
        public static class Nacos {

            /**
             * 是否从 Nacos 读取提示词。
             *
             * <p>关掉它就是"纯本地文件"模式（离线开发、Nacos 没起来时很有用）。
             * 注意关掉不会导致提示词为空 —— 会回落到本地文件与代码兜底。</p>
             */
            private boolean enabled = true;

            private String serverAddr = "127.0.0.1:8848";

            private String username = "nacos";

            private String password = "nacos";

            private String group = "DEFAULT_GROUP";

            /**
             * 命名空间。
             *
             * <p>★ <b>这个参数必须与"发布配置时选的命名空间"严格一致，否则会静默读不到。</b>
             * 本次实测踩到了：用 HTTP API 发布配置时没带 tenant 参数（落在 <b>空</b> 命名空间），
             * 而 Nacos 3.x 客户端默认查的是 <b>public</b> —— 两者在服务端是<b>两个不同</b>
             * 的命名空间（3.x 之前 public 的 ID 是空串，之后是 {@code public}，
             * 这个变化让"发布成功但读不到"变得很容易发生）。
             *
             * <p>它的表现极具迷惑性：<b>发布接口返回 true、配置列表里也能看到、
             * 但客户端 getConfig 返回 null（＝"没配置过"），不打任何错误日志</b>。
             * 所以：<b>看到"配置明明在，服务却说没有"时，第一件事就是对命名空间。</b></p>
             */
            private String namespace = "public";

            /**
             * dataId 前缀。每个 Agent 一个配置项：
             * {@code fm-aigc-prompt-route} / {@code -consult} / ...
             *
             * <p>★ 每个 Agent 单独一个 dataId（而不是塞在一个大配置里），
             * 这样在 Nacos 控制台上能一个一个改、一个一个看历史版本 ——
             * 调提示词是个高频操作，"改 A 不小心碰到 B"的成本比多几个配置项高得多。</p>
             */
            private String dataIdPrefix = "fm-aigc-prompt-";

            private long timeoutMs = 3000L;

            /**
             * 提示词缓存有效期（毫秒）：超过它就主动去 Nacos 重新拉一次。
             *
             * <p>★ 这是"配置变更最终生效"的<b>兜底保证</b>，不依赖 Nacos 的推送。
             * 推送（listener）能工作时是秒级生效；不能工作时靠这个在 TTL 内生效。
             * 默认 30 秒 —— 对"改提示词"这个场景足够快，而开销可以忽略
             * （getConfig 走客户端本地缓存 + 服务端比对 MD5，不传内容）。</p>
             */
            private long refreshIntervalMs = 30_000L;
        }
    }

    /**
     * RAG（检索增强）配置。
     *
     * <p>★ 这些参数都是<b>会被人反复调</b>的：topK、相似度阈值、分块大小、
     * 是否开混合检索。所以它们全部外置并且能被验收脚本读到 ——
     * "改一行参数，2 分钟看出命中率涨了还是跌了"是这一节的核心目标。</p>
     */
    @Data
    public static class Rag {

        /**
         * Embedding 通道：{@code bailian}（阿里百炼，真实语义向量）或
         * {@code deterministic}（内置确定性向量，离线兜底）。
         *
         * <p>★ 为什么要有一个"假的"向量模型 —— 和 MockChatModel 完全同一个理由：
         * <b>外部依赖不可控的东西，必须有一个能在无网环境跑起来的替身</b>。
         * 它的价值不是"替代真模型"（哈希投影没有语义），而是
         * "让链路在没网 / 没 key / 没额度时依然能被自动验收"。</p>
         *
         * <p>⚠️ 两套模型的向量**互不兼容**（一个 1024 维真实语义、一个哈希投影），
         * 所以索引名会自动带上模型标识（见 {@link #indexName()}）——
         * 混用会造成"能查到内容但相关性全错"，那是典型的静默降级。</p>
         */
        private String provider = "bailian";

        /** 百炼的 OpenAI 兼容地址（embedding 与 chat 是两套不同的 base-url，必须分开配） */
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

        /**
         * embedding 的 key。
         *
         * <p>⚠️ 默认值必须是**非空**的 {@code not-configured}：OpenAiApi 在构造时
         * 断言 apiKey 非空，留空会让服务启动失败。一个"没配 key"的配置问题
         * 不该升级成"服务起不来"，它应该表现为"检索降级为空"（见 EmbeddingConfig）。</p>
         */
        private String apiKey = "not-configured";

        /** 百炼的 embedding 模型。text-embedding-v4 = Qwen3-Embedding 系列，支持自定义维度 */
        private String model = "text-embedding-v4";

        /**
         * 向量维度。
         *
         * <p>★★ <b>维度是索引 schema 的一部分</b>：dense_vector 的 dims 是**不可变**的，
         * 换模型 / 换维度 = 旧索引直接不可用，必须重建。
         * 本项目把维度写进索引名，就是为了让"换了模型但没重建索引"这件事
         * 在名字上就暴露出来，而不是变成"查询能跑但结果全错"。</p>
         */
        private int dimensions = 1024;

        /** 索引名前缀。完整名 = {prefix}_{模型标识}_{维度}（见 {@link #indexName()}） */
        private String indexPrefix = "fitmind_knowledge";

        /** Elasticsearch 地址（9200 被 Cpolar 占用，本项目 ES 在 9201） */
        private String esHost = "127.0.0.1";
        private int esPort = 9201;

        /**
         * 检索返回条数。
         *
         * <p>★ 常见实现这里是 {@code topK(999)} + {@code query("")}，
         * 等价于"把整个知识库塞进 prompt"。那样做的后果有三个：
         * ① 每轮 token 费用与延迟被放大约 100 倍；② 真正相关的内容被淹在噪声里，
         * 回答质量反而下降；③ prompt 迟早超长直接报错。
         * 4 条是本项目语料规模下的合理值：够覆盖一个问题的依据，又不至于淹掉重点。</p>
         */
        private int topK = 4;

        /**
         * 相似度阈值：低于它的检索结果**不送进 prompt**。
         *
         * <p>★ 这是"宁可拒答，不要误导"的落点。没有阈值的话，
         * 知识库里没有相关内容时模型仍会拿到 4 条"最不相关的内容"，
         * 然后一本正经地基于它们编答案 —— 比直接说"不知道"危险得多。</p>
         */
        private double similarityThreshold = 0.55;

        /**
         * 是否开启"关键词 + 向量"混合检索。
         *
         * <p>★ 这是选 ES 而不是别的向量库时顺带拿到的好处：ES 同时是全文检索引擎。
         * 纯向量检索在两种问题上会漏：① 精确术语（"臀桥"、"RPE" 这类词
         * 用语义向量找反而不如字面匹配准）；② 数字与专有名词。
         * 混合检索把两路召回用 RRF 融合，代价只是多一次查询。</p>
         */
        private boolean hybrid = true;

        /** 关键词一路的召回条数（融合前） */
        private int keywordTopK = 6;

        /** 向量一路的召回条数（融合前，取大于 topK 再融合，避免融合后不够 topK 条） */
        private int vectorTopK = 8;

        /** 分块字符数上限（中文语料按字符切比按 token 切更贴合真实长度，见 MarkdownCorpusLoader） */
        private int chunkSize = 600;

        /** 相邻分块的重叠字符数：避免答案正好被切在分块边界上而检索不到 */
        private int chunkOverlap = 120;

        /** 送进 prompt 的检索内容字符上限（成本护栏：检索异常时不能把 prompt 打爆） */
        private int maxContextChars = 4000;

        /**
         * 语料位置。
         *
         * <p>用 {@code classpath*:}（带星号）而不是 {@code classpath:}：
         * 前者会扫描所有 classpath 条目，后者只匹配第一个 ——
         * 打成 fat jar 之后语料在 {@code BOOT-INF/classes} 里，
         * 两者行为差别会在"本地 IDE 跑得好好的、jar 一跑就找不到语料"时暴露出来。</p>
         */
        private String corpusLocation = "classpath*:knowledge/*.md";

        /**
         * 派生出来的索引名：{@code {prefix}_{模型标识}_{维度}}。
         *
         * <p>★ 刻意做成"算出来的"而不是"配出来的"：索引名与 embedding 模型
         * 必须严格对应，而"两个配置项要保持一致"是个早晚会出错的约定
         * （本项目已经踩过两次同类问题）。让它自动派生，人就没有写错的机会。</p>
         */
        public String indexName() {
            String tag = (model == null ? "unknown" : model).replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
            return indexPrefix + "_" + tag + "_" + dimensions;
        }
    }

    /**
     * 工具调用配置。
     */
    @Data
    public static class Tool {

        /**
         * 单轮对话里允许的工具调用次数上限。
         *
         * <p>★ 这是<b>成本与体验的护栏</b>：模型有时会连续调工具
         * （查一次不满意、换个关键词再查），没有上限时一轮对话能打十几次接口，
         * 用户等半分钟还看不到一个字。</p>
         *
         * <p>超限时工具返回一句可读的提示（让模型基于已有信息作答），
         * <b>而不是抛异常</b> —— 抛异常会直接把对话打断，用户看到的是"系统错误"。</p>
         */
        private int maxCallsPerTurn = 4;

        /** 是否把工具调用记录落进 {@code chat_message.tool_calls}（复盘要看得见"调了什么"） */
        private boolean recordInvocations = true;
    }
}
