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
}
