package com.fitmind.aigc.prompt;

import cn.hutool.core.util.StrUtil;
import com.fitmind.aigc.agent.AgentType;
import com.fitmind.aigc.constants.AigcConstants;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提示词管理器 —— 三级来源，保证"永远拿得到提示词"。
 *
 * <pre>
 *   ① Nacos 配置（可热更新，改完立刻生效）
 *   ② classpath:prompts/{key}.txt（跟代码一起发版）
 *   ③ 本类里的代码兜底常量
 * </pre>
 *
 * <p>★ <b>三级而不是一级，是对常见实现一个真实故障的修正。</b>
 * 常见实现只在启动时从 Nacos 读一次，失败只打 error 日志，
 * 缓存的 {@code AtomicReference} 保持 null → 模型收到的 system 提示是空串。
 * 后果是提示词里写的一切（人设、风格、安全边界、输出格式）全部失效，
 * 表现是"AI 突然变得不受控"，而排查时容易往模型或网络上找原因。</p>
 *
 * <p>有了这三级，"提示词为空"在结构上不可能发生 ——
 * 最差的情况是回落到代码里那一段通用提示，功能仍然完整。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptManager {

    /** 纯模型对话（SIMPLE 策略）用的提示词 key —— 它不是 Agent，但同样需要一份人设 */
    public static final String SIMPLE_KEY = "simple";

    /**
     * 第三级兜底：代码里的最小提示词。
     *
     * <p>它不是给日常用的（正常情况下永远走不到这里），存在的意义是
     * <b>让"提示词为空"这件事不可能发生</b>。内容刻意写成最小集：
     * 人设 + 安全边界 —— 这两条丢了才是真会出问题的。</p>
     */
    private static final String CODE_FALLBACK =
            "你是 FitMind 的 AI 健身教练「小飞」。只处理健身训练、营养、恢复相关的问题，"
                    + "回答要直接、给可执行的做法。涉及疾病诊断、用药、伤病治疗时，"
                    + "明确说明你判断不了并建议就医，不要给治疗方案。";

    private final LocalPromptSource localSource;
    private final ObjectProvider<NacosPromptSource> nacosProvider;

    /**
     * 启动时预热。
     *
     * <p>★ 为什么要预热：不做的话，<b>第一次提问会额外花一次 Nacos 往返</b>，
     * 而这段延迟会算在这个用户的首字延迟里（用户能明显感觉到"第一句特别慢"）。
     * 预热之后所有读取都是内存操作。</p>
     *
     * <p>顺带把"每个提示词最终用的哪一级来源"打成一行汇总日志 ——
     * 排查"为什么我改了 Nacos 没生效"时，第一眼看的就是这行。</p>
     */
    @PostConstruct
    public void warmUp() {
        // ★ 必须先真正"取一遍"，再汇总。
        //
        //   这里踩过一个真实的坑：最初 warmUp 里直接调 describe()，
        //   而 describe() 只看缓存（不触发加载）—— 于是"预热"什么都没做，
        //   第一次提问仍然要等一次 Nacos 往返，`/prompts` 接口也会显示成
        //   "用的是本地文件"（因为缓存里还什么都没有）。
        //   表现是"预热看起来执行了、日志也打了，但实际没生效"。
        for (AgentType type : AgentType.values()) {
            get(type);
        }
        get(SIMPLE_KEY);
        // ★ M6-5：摘要提示词也一起预热 —— 它在"对话变长之后的某一轮"才会被用到，
        //   不预热的话，那一轮会额外吃一次 Nacos 往返（正好加在首字延迟上，
        //   表现为"聊到某个长度后突然变慢"，很难联想到是提示词没预热）
        get(AigcConstants.COMPRESS_PROMPT_KEY);

        Map<String, String> summary = describe();
        StringBuilder sb = new StringBuilder();
        summary.forEach((k, v) -> sb.append("\n    · ").append(k).append(" → ").append(v));
        log.info("★ 提示词加载完成（{} 项）：{}", summary.size(), sb);
    }

    public String get(AgentType type) {
        return get(type.key());
    }

    public String get(String key) {
        if (StrUtil.isBlank(key)) {
            return CODE_FALLBACK;
        }
        // ① Nacos
        NacosPromptSource nacos = nacosProvider.getIfAvailable();
        if (nacos != null) {
            String text = nacos.load(key);
            if (StrUtil.isNotBlank(text)) {
                return text;
            }
        }
        // ② 本地文件
        String text = localSource.load(key);
        if (StrUtil.isNotBlank(text)) {
            return text;
        }
        // ③ 代码兜底
        log.warn("提示词 [{}] 既不在 Nacos 也没有本地文件，使用代码兜底", key);
        return CODE_FALLBACK;
    }

    /**
     * 各提示词的当前来源 —— 给排查与管理端接口用。
     *
     * <p>★ 加这个方法是因为一个很常见的困惑：<b>"我改了 Nacos，怎么没生效？"</b>
     * 可能的原因有一堆（dataId 拼错、group 不对、配置根本没发布、
     * 改的是另一个环境）。有一个"当前到底用的哪一份"的查询口，
     * 一行请求就能定位，不用去猜。</p>
     */
    public Map<String, String> describe() {
        Map<String, String> result = new LinkedHashMap<>();
        for (AgentType type : AgentType.values()) {
            result.put(type.key(), describeOne(type.key()));
        }
        result.put(SIMPLE_KEY, describeOne(SIMPLE_KEY));
        // M6-5：上下文压缩用的摘要提示词（它不是 Agent，但同样是可外置的提示词）
        result.put(AigcConstants.COMPRESS_PROMPT_KEY, describeOne(AigcConstants.COMPRESS_PROMPT_KEY));
        return result;
    }

    private String describeOne(String key) {
        NacosPromptSource nacos = nacosProvider.getIfAvailable();
        if (nacos != null) {
            // ★ 先 load 一次（受 TTL 约束）再判断来源。
            //   直接 has() 只能看到"缓存里有没有"，在没有预先加载时
            //   会把"Nacos 上有配置但还没拉过"误报成"用的是本地文件"。
            nacos.load(key);
            if (nacos.has(key)) {
                return nacos.name();
            }
        }
        String local = localSource.load(key);
        if (StrUtil.isNotBlank(local)) {
            return localSource.name() + "（" + local.length() + " 字）";
        }
        return "code-fallback";
    }
}
