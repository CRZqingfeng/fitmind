package com.fitmind.aigc.rag;

import com.fitmind.aigc.constants.AigcConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Markdown 分块器。
 *
 * <p><b>★ 为什么不用 Spring AI 的 {@code TokenTextSplitter}（方案里原本写的是它）：</b>
 * 两个实测出来的理由：
 * <ol>
 *   <li><b>中文按 token 切不划算</b>。{@code TokenTextSplitter} 用的是 cl100k 的
 *       token 计数，对英文大致是"1 token ≈ 4 字符"，而中文常常是"1 个汉字 ≈ 1~2 个 token"。
 *       同样的 token 预算下，中文实际能装的内容少得多且不稳定 ——
 *       在中文语料上按字符切反而更贴合真实长度。</li>
 *   <li><b>Markdown 有天然的语义边界</b>。按 {@code ##} 标题切出来的每一块
 *       是一个完整的知识单元（"蛋白质摄入量"、"训练频率"），
 *       而按固定长度硬切会把一个要点切成两半 ——
 *       检索到半句话的依据，比检索不到更糟。</li>
 * </ol>
 * 所以这里：<b>先按标题切（语义边界）→ 超长再按段落贪心打包 → 相邻块加重叠</b>。</p>
 *
 * <p>★ 每块都带上 {@code section}（标题路径）：检索结果可以显示成
 * "《增肌期该怎么吃》&gt; 蛋白质摄入量"，用户能自己核对依据对不对 ——
 * 这是"溯源"能不能真正可用的关键，只给一个文档标题的话用户还得自己翻全文。</p>
 */
@Slf4j
public class MarkdownChunker {

    private final int chunkSize;
    private final int chunkOverlap;

    /**
     * 低于这个长度的块没有独立检索价值：它答不了一个完整问题，却会占掉一个 topK 名额。
     *
     * <p>★ 150 是量出来的：实测注入的 162 条上下文里，<b>&lt;150 字的碎片占 20%</b>，
     * 而它们的长度中位才 204 字 —— 150 正好切在"残块"和"能独立成义的一块"之间。</p>
     */
    private static final int MIN_CHUNK_CHARS = 150;

    public MarkdownChunker(int chunkSize, int chunkOverlap) {
        this.chunkSize = Math.max(120, chunkSize);
        // 重叠不能超过一块的一半：否则两块会大面积重复，
        // 检索时会同时命中"同一段内容的两份"，白白占掉 topK 的名额
        this.chunkOverlap = Math.min(Math.max(0, chunkOverlap), this.chunkSize / 2);
    }

    /**
     * 分块。
     *
     * @param corpus 一篇语料
     * @return 分块后的文档，id 形如 {@code {docId}#{chunkIndex}} ——
     *         <b>刻意不用随机 id</b>：重建索引必须是"覆盖"，
     *         否则每重建一次知识库就多出一份内容，检索结果里会出现重复段落
     */
    public List<Document> split(MarkdownCorpusLoader.CorpusDocument corpus) {
        List<Document> chunks = new ArrayList<>();
        List<Section> sections = splitByHeading(corpus.body());
        int index = 0;
        for (Section section : sections) {
            for (String piece : mergeTinyTail(pack(section.content()))) {
                if (piece.isBlank()) {
                    continue;
                }
                Map<String, Object> metadata = new HashMap<>(corpus.metadata());
                metadata.put(AigcConstants.META_SECTION, section.heading());
                metadata.put(AigcConstants.META_CHUNK_INDEX, index);
                chunks.add(Document.builder()
                        .id(corpus.docId() + "#" + index)
                        .text(piece)
                        .metadata(metadata)
                        .build());
                index++;
            }
        }
        return chunks;
    }

    /** 按 markdown 标题切段。标题行本身也会留在内容里 —— 它常常就是最好的检索关键词 */
    private List<Section> splitByHeading(String body) {
        List<Section> sections = new ArrayList<>();
        String currentHeading = "";
        StringBuilder buffer = new StringBuilder();
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            // ★★ 只有 markdown 标题（#/##/###）才算章节边界。
            //   曾经的写法还把"整行加粗 `**起始**`"也当成标题 —— 语料里有 34 行这种东西
            //   （03 13 行、11 10 行、06 4 行…），于是切出一堆 74~95 字的"小节"：
            //   `起始`、`执行`、`手腕`、`常见原因`。
            //   这些块**结构上就不可能被判"相关"**（一个 76 字的"起始"回答不了任何完整问题），
            //   实测注入的 162 条上下文里有 20% 是 <150 字的碎片，直接把上下文精度压到 0.79。
            //   加粗只是**段内的子标签**，应该留在正文里，不该打断小节。
            boolean isHeading = trimmed.startsWith("#");
            if (isHeading && buffer.length() > 0) {
                sections.add(new Section(currentHeading, buffer.toString().trim()));
                buffer.setLength(0);
            }
            if (isHeading) {
                currentHeading = trimmed.replaceAll("^#+\\s*", "").replaceAll("\\*", "");
                // 标题只作为 heading 元数据，不再重复写进正文：
                // 否则每次检索都会命中标题、每块都"像是相关的"
                continue;
            }
            buffer.append(line).append("\n");
        }
        if (buffer.length() > 0) {
            sections.add(new Section(currentHeading, buffer.toString().trim()));
        }
        return sections;
    }

    /**
     * 段落贪心打包：装不下就切块，并把尾部若干段带进下一块作为重叠。
     *
     * <p>重叠的作用很具体：一个问题的答案正好被切在分块边界上时，
     * 两块各自都"不完整"、相似度都不够高，于是检索不到。
     * 带上重叠之后，完整表述会出现在某一块里。</p>
     */
    private List<String> pack(String text) {
        List<String> result = new ArrayList<>();
        if (text.length() <= chunkSize) {
            result.add(text);
            return result;
        }
        List<String> paragraphs = splitParagraphs(text);
        List<String> current = new ArrayList<>();
        int currentLen = 0;
        for (String paragraph : paragraphs) {
            // 单段就超长（表格、长代码块）→ 硬切，否则它会永远装不进去（死循环风险）
            if (paragraph.length() > chunkSize) {
                if (!current.isEmpty()) {
                    result.add(String.join("\n", current));
                    current = new ArrayList<>();
                    currentLen = 0;
                }
                for (int i = 0; i < paragraph.length(); i += chunkSize) {
                    result.add(paragraph.substring(i, Math.min(paragraph.length(), i + chunkSize)));
                }
                continue;
            }
            if (currentLen + paragraph.length() > chunkSize && !current.isEmpty()) {
                result.add(String.join("\n", current));
                // 保留尾部作为重叠
                List<String> overlap = new ArrayList<>();
                int len = 0;
                for (int i = current.size() - 1; i >= 0 && len < chunkOverlap; i--) {
                    overlap.add(0, current.get(i));
                    len += current.get(i).length();
                }
                current = overlap;
                currentLen = len;
            }
            current.add(paragraph);
            currentLen += paragraph.length();
        }
        if (!current.isEmpty()) {
            result.add(String.join("\n", current));
        }
        return result;
    }

    /**
     * 把过短的尾块并回上一块。
     *
     * <p>★★ <b>为什么要这一步</b>：{@code pack()} 在两种情况下会留下很短的一块 ——
     * ① 一节正好比 chunkSize 多一点，尾部只剩几十字；
     * ② 单段超长（参考资料列表这种没有空行的长段）走硬切，最后一片是余数。
     * 实测 155 块里有 <b>42 块</b>不足 150 字，最短 50 字。</p>
     *
     * <p>这类块<b>不是"小而精"，是"残"</b>：它答不了任何完整问题，但会真实占掉一个
     * topK 名额 —— 上下文精度就是被它们拉下来的（注入 162 条里 20% 是碎片）。</p>
     *
     * <p>★ 合并而不是丢弃：chunkSize 是<b>目标值</b>不是硬上限，
     * 为了丢掉 50 字而损失内容不划算。合并后这一块可能略超 600 字，可以接受。</p>
     */
    private List<String> mergeTinyTail(List<String> pieces) {
        List<String> merged = new ArrayList<>();
        for (String piece : pieces) {
            if (!merged.isEmpty() && piece.length() < MIN_CHUNK_CHARS) {
                int last = merged.size() - 1;
                merged.set(last, merged.get(last) + "\n" + piece);
            } else {
                merged.add(piece);
            }
        }
        return merged;
    }

    private List<String> splitParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        for (String block : text.split("\n\\s*\n")) {
            String trimmed = block.trim();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }
        return paragraphs;
    }

    /** 一个标题段 */
    private record Section(String heading, String content) {
    }
}
