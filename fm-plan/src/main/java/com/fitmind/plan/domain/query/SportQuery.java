package com.fitmind.plan.domain.query;

import com.fitmind.common.domain.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 运动项目分页查询。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "运动项目分页查询")
public class SportQuery extends PageQuery {

    @Schema(description = "分类：日常/走路/跑步/骑行/有氧操/舞蹈瑜伽/游泳/球类运动/跳绳/爬楼梯/其他",
            example = "跑步")
    private String category;

    @Schema(description = "关键字：运动名模糊匹配", example = "跑")
    private String keyword;
}
