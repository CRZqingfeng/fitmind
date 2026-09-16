package com.fitmind.data.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 课程销量榜条目。
 *
 * <p>★ 这里<b>只有 courseId，没有课程名</b>：课程名在 fm-course，
 * 数据中心不直连业务库（这是本模块的立身之本）。
 * 名字由调用方（管理端前端）拿 id 去课程接口批量查 ——
 * 或者将来走 fm-api 的 CourseClient 补一次批量查询。</p>
 *
 * <p>这不是"偷懒没做"，而是刻意划清边界：
 * 一旦数据中心开始同步业务实体的名称/封面，
 * 它就要跟着业务表的字段变化而变化，还会存下过期快照。</p>
 */
@Data
@Accessors(chain = true)
@Schema(description = "课程销量榜条目")
public class CourseTopVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "排名（从 1 开始）")
    private Integer rank;

    @Schema(description = "课程id")
    private Long courseId;

    @Schema(description = "销量")
    private Integer orderCount;

    @Schema(description = "销售额（分）")
    private Long orderAmount;

    @Schema(description = "退款笔数")
    private Integer refundCount;

    @Schema(description = "数据来源：realtime / archived")
    private String source;
}
