package com.fitmind.aigc.rag;

import com.fitmind.aigc.constants.AigcConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识语料加载器：读 {@code classpath:knowledge/*.md} 并解析 front-matter 元数据。
 *
 * <p><b>为什么自己写，不用 Spring AI 的 {@code MarkdownDocumentReader}：</b>
 * 需求只有两条 —— "读文件"和"解析 front-matter"，而我们要的元数据格式
 * 比通用的那套更严格（{@code goal: [muscle_gain, fat_loss]} 这种列表值必须被解析成
 * 字符串列表，否则 metadata 过滤会匹配不上）。自己写 80 行，换来的是
 * <b>完全可控且能被验收脚本逐条断言</b>的解析行为，比猜第三方解析器的边界情况划算。</p>
 *
 * <p>★ <b>metadata 是检索质量的关键</b>，不是"顺手带的标签"：
 * 一个目标是减脂的用户问"该怎么吃"，元数据过滤能把"增肌期怎么吃"这类文档
 * 直接排除掉 —— 这比调 topK 有效得多，因为 topK 只能控制"给几条"，
 * 控制不了"给的是不是对的"。所以本类解析出来的元数据会一路带到 ES 的过滤字段上。</p>
 */
@Slf4j
public class MarkdownCorpusLoader {

    private final String location;

    public MarkdownCorpusLoader(String location) {
        this.location = location;
    }

    /**
     * 加载全部语料。
     *
     * <p>⚠️ 加载失败（目录写错、文件读不出来）时<b>必须抛异常</b>，不能"返回空列表继续跑"。
     * 语料为空 + 检索为空 + 拒答，这三个现象会叠在一起，
     * 排查时最容易误判为"阈值配太高"或"embedding 有问题"，
     * 而真正的原因其实是路径写错了 —— 一个能报错的问题不该伪装成效果问题。</p>
     */
    public List<CorpusDocument> load() {
        List<CorpusDocument> result = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(location);
            // 按文件名排序，保证"重建索引"的结果是确定的（顺序变了 chunk_index 就会变，
            // 那样每次重建的文档 id 都不同，diff 环境时会很难受）
            List<Resource> sorted = new ArrayList<>(List.of(resources));
            sorted.sort((a, b) -> String.valueOf(a.getFilename()).compareTo(String.valueOf(b.getFilename())));
            for (Resource resource : sorted) {
                String filename = resource.getFilename();
                if (filename == null || !filename.endsWith(".md")) {
                    continue;
                }
                String raw = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                result.add(parse(filename, raw));
            }
        } catch (IOException e) {
            throw new IllegalStateException("知识语料加载失败（检查路径：" + location + "）：" + e.getMessage(), e);
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("知识语料为空（" + location + "）—— 检索会永远返回空，"
                    + "而表现是'AI 什么都不知道'，很容易被误判成阈值或模型问题");
        }
        log.info("★ 知识语料加载完成：{} 篇，共 {} 字", result.size(),
                result.stream().mapToInt(d -> d.body().length()).sum());
        return result;
    }

    /**
     * 解析一篇语料：front-matter + 正文。
     *
     * <p>格式（本项目自定义，刻意保持极简）：
     * <pre>
     * ---
     * title: 增肌期该怎么吃
     * category: nutrition
     * goal: [muscle_gain]
     * level: beginner
     * source: 自建
     * ---
     * （正文）
     * </pre>
     * </p>
     */
    private CorpusDocument parse(String filename, String raw) {
        String docId = filename.substring(0, filename.length() - 3);
        String text = raw.replace("\r\n", "\n");
        Map<String, Object> metadata = new LinkedHashMap<>();

        if (text.startsWith("---\n")) {
            int end = text.indexOf("\n---", 3);
            if (end < 0) {
                throw new IllegalStateException("语料 " + filename + " 的 front-matter 没有结束标记 '---'");
            }
            String frontMatter = text.substring(4, end);
            for (String line : frontMatter.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int colon = trimmed.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, colon).trim();
                String value = trimmed.substring(colon + 1).trim();
                metadata.put(key, parseValue(value));
            }
            // 跳过 front-matter 与紧随其后的换行
            int bodyStart = text.indexOf('\n', end + 1);
            text = bodyStart < 0 ? "" : text.substring(bodyStart + 1);
        }

        String title = String.valueOf(metadata.getOrDefault("title", docId));
        metadata.putIfAbsent("category", "unknown");
        metadata.putIfAbsent("source", "自建");
        metadata.putIfAbsent("level", "beginner");
        metadata.put(AigcConstants.META_DOC_ID, docId);
        metadata.put(AigcConstants.META_TITLE, title);

        return new CorpusDocument(docId, title, metadata, text.trim());
    }

    /** {@code [a, b]} → List；其余按字符串。空值返回空串而不是 null（null 会被 ES 拒收） */
    private Object parseValue(String value) {
        if (value.startsWith("[") && value.endsWith("]")) {
            String inner = value.substring(1, value.length() - 1).trim();
            if (inner.isEmpty()) {
                return List.of();
            }
            List<String> items = new ArrayList<>();
            for (String item : inner.split(",")) {
                String v = StringUtils.trimWhitespace(item).replaceAll("^[\"']|[\"']$", "");
                if (!v.isEmpty()) {
                    items.add(v);
                }
            }
            return items;
        }
        return value.replaceAll("^[\"']|[\"']$", "");
    }

    /**
     * 一篇语料（front-matter 已解析，正文未分块）。
     *
     * @param docId    文档标识（取自文件名，也是分块 id 的前缀）
     * @param title    标题（检索结果的溯源显示用）
     * @param metadata front-matter 解析出来的元数据
     * @param body     正文（不含 front-matter）
     */
    public record CorpusDocument(String docId, String title, Map<String, Object> metadata, String body) {
    }
}
