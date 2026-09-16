package com.fitmind.trade.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.experimental.Accessors;
import lombok.Data;

/**
 * 购物车条目。
 */
@Data
@Accessors(chain = true)
@Schema(description = "购物车条目")
public class CartVO {

    @Schema(description = "课程id（字符串，避免前端精度丢失）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long courseId;

    @Schema(description = "课程名称（取课程服务的最新值，不是加入时的快照）")
    private String courseName;

    @Schema(description = "封面")
    private String coverUrl;

    /**
     * 当前价格（分）。
     *
     * <p>注意这里是<b>课程服务的最新价</b>，不是加入购物车时的快照价 ——
     * 购物车展示必须显示现价，否则用户会拿着一个过期价格去预期。</p>
     */
    @Schema(description = "最新价格（分）")
    private Integer price;

    @Schema(description = "加入时的价格（分），与最新价不同说明变价了")
    private Integer addedPrice;

    @Schema(description = "是否失效：课程已下架/已删除")
    private Boolean invalid;

    @Schema(description = "失效原因，invalid=true 时有值")
    private String invalidReason;

    @Schema(description = "是否已购买过（买过的课程不该出现在购物车里）")
    private Boolean bought;
}
