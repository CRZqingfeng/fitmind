package com.fitmind.learning.domain.query;

import com.fitmind.common.domain.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 问题分页查询（用户端）。
 *
 * <p>★ <b>M10-1 第 3 期：加了 {@code scope} 开关</b>。
 * 原先有一条硬校验「课程id和训练动作id不能都为空」（因为"全站问题列表没有业务意义"）。
 * 但 M10-1 第 3 期要把社区页做成<b>答疑广场</b> —— 默认就是要看全站的。</p>
 *
 * <p>★★ <b>为什么不直接把那条校验删掉</b>：`accept-web-m7-5.mjs:262-273` 有 3 条负向断言
 * （测试标题写着"假绿过一次的地方"）钉着它：不带上下文必须 400。
 * 删掉校验会让 3 条有历史教训的断言集体变红。</p>
 *
 * <p>→ 所以做成<b>显式开关</b>：不带 {@code scope} = 行为一字不变（仍 400）；
 * 只有显式传 {@code scope=ALL} 才放开。**"放开"变成一个需要签名的动作**，
 * 不会被谁漏传参数就悄悄变成全站查询。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "问题分页查询")
public class QuestionPageQuery extends PageQuery {

    /** {@code scope} 的取值：全站广场（不受 course/section 约束） */
    public static final String SCOPE_ALL = "ALL";

    @Schema(description = "课程id")
    private Long courseId;

    @Schema(description = "训练动作id")
    private Long sectionId;

    @Schema(description = "是否只看我提问的")
    private Boolean onlyMine;

    @Schema(description = "浏览范围：不传=按 course/section 过滤（默认，二者必须有一个）；"
            + "ALL=全站广场（忽略 course/section）", example = "ALL")
    private String scope;

    /**
     * 只看**还没有人回答**的问题（`answer_times = 0`）—— 广场的「待回答」页签。
     *
     * <p>它同时是 AI 机器人上线后的**唯一作用域**：机器人只补空位、不抢已被真人回答的问题
     * （见 `docs/M10-1-学习互动区开发方案.md` §六）。</p>
     */
    @Schema(description = "只看待回答（answer_times = 0）")
    private Boolean unanswered;

    /**
     * 排序：不传 = 最新（`create_time desc`）；`hot` = 最热（`answer_times desc, create_time desc`）。
     *
     * <p>★ 注意「最热」用的是**回答数**而不是点赞数 —— 因为 `interaction_question`
     * 本身没有点赞字段（点赞在回答上）。回答数才是"这个问题被认真对待了"的信号。</p>
     */
    @Schema(description = "排序：不传=最新；hot=最热（按回答数）", example = "hot")
    private String sort;

    /** 是否全站广场模式 */
    public boolean isAllScope() {
        return SCOPE_ALL.equalsIgnoreCase(scope);
    }
}
