package com.fitmind.aigc.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@Schema(description = "发起一轮对话")
public class ChatRequestDTO {

    /**
     * 会话id。为空时服务端新建一个会话并把 id 回给前端。
     *
     * <p>★ 注意这里<b>只接收 sessionId，不接收 userId</b> —— 用户身份永远从
     * {@code UserContext} 取。让前端传 userId 等于把越权入口开在最外层，
     * 而 LLM 场景里这个风险还会被放大（模型可能"编造"参数，见 M6-4 的工具安全）。</p>
     */
    @Size(max = 64, message = "会话id过长")
    @Schema(description = "会话id，不传则新建会话")
    private String sessionId;

    @NotBlank(message = "说点什么吧")
    @Size(max = 1000, message = "单次提问不超过 1000 字")
    @Schema(description = "提问内容")
    private String content;

    /**
     * 本次使用的编排策略，可选：{@code ROUTE} / {@code ENHANCE} / {@code SIMPLE}。
     *
     * <p>不传就用配置里的默认值（{@code fm.ai.chat.type}）。</p>
     *
     * <p>★ 为什么允许请求级覆盖 —— 编排策略本来是部署级决策，看起来不该由单次请求决定。
     * 但有两个场景确实需要它：
     * ① <b>演示与对比</b>：同一个提问在三种策略下各跑一遍，看延迟、模型调用次数、
     *    回答质量的差别（这个对比本身就很能说明"多 Agent 到底值不值"）；
     * ② <b>验收</b>：脚本要能覆盖三条路径，而不是"改配置 + 重启 + 再跑一遍"。
     * 线上不会有人传这个参数（前端也不会给入口），所以它的风险面很小。</p>
     */
    @Schema(description = "编排策略（可选）：ROUTE / ENHANCE / SIMPLE")
    private String chatType;

    /**
     * 用户训练目标（可选），用于知识库检索的<b>元数据过滤</b>。
     *
     * <p>取值：{@code muscle_gain} / {@code fat_loss} / {@code strength} / {@code endurance} 等，
     * 与语料 front-matter 里的 {@code goal} 字段对应。不传就不过滤。</p>
     *
     * <p>★ 为什么这个参数可以由请求指定（而 {@code userId} 绝对不行）：
     * 它只影响<b>检索范围</b>，不参与任何鉴权判断 —— 就算有人传一个自己不匹配的目标，
     * 最坏结果是"检索到的知识不贴合他的实际情况"，不会让他看到别人的数据。
     * <b>判断一个参数能不能由客户端传，看的是"它能影响权限吗"。</b></p>
     *
     * <p>M6-4 起这个值会由 {@code BodyTools} 从体测档案里读出来（不再由前端传），
     * 到时候保留参数的唯一原因是让验收脚本能精确构造"减脂用户 / 增肌用户"两种场景。</p>
     */
    @Schema(description = "训练目标（可选，用于知识库元数据过滤）：muscle_gain / fat_loss 等")
    private String goal;

    /**
     * 检索返回条数覆盖（可选）。
     *
     * <p>★ 和 {@code chatType} 一样是"运维与验收用"的参数：调 RAG 效果的本质是
     * <b>改一个参数、看命中率变了没有</b>。如果每次都改配置 + 重启才能试一次，
     * 调参就退化成猜。有了它，"topK=1 和 topK=8 的命中率差多少"
     * 可以在一次脚本里跑完。</p>
     */
    @Schema(description = "检索返回条数覆盖（可选），不传用配置默认值")
    private Integer ragTopK;

    /**
     * 相似度阈值覆盖（可选），取值 0~1。
     *
     * <p>★ 验收里断言"阈值真的生效"靠的就是它：把阈值调到 0.99，
     * 检索应该几乎全部落空（走到拒答）；调回默认值则恢复正常。
     * 没有这个入口的话，"阈值配了但没生效"这类问题只能用肉眼看回答来猜。</p>
     */
    @Schema(description = "相似度阈值覆盖（可选，0~1），不传用配置默认值")
    private Double ragThreshold;

    /**
     * 是否启用混合检索（关键词 + 向量）覆盖（可选）。
     *
     * <p>★ 它存在的唯一理由是<b>让"混合检索带来多少提升"变成一个可比较的数字</b>：
     * 同一套评估集跑两次（开 / 关），命中率差值就是答案。
     * 方案对比如果不能在同一个进程里跑，结论就只能靠嘴说。</p>
     */
    @Schema(description = "是否启用混合检索覆盖（可选），不传用配置默认值")
    private Boolean ragHybrid;
}
