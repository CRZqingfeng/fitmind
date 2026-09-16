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
}
