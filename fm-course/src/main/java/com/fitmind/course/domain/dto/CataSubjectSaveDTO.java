package com.fitmind.course.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 保存「训练节点 ↔ 题目」关联的请求。
 *
 * <p>提交时是一个数组，每个元素代表一个节点挂了哪些题：</p>
 * <pre>
 *   [ { cataId: 1001, subjectIds: [1, 2] },   // 哑铃卧推下挂 2 道题
 *     { cataId: 1002, subjectIds: [3] } ]     // 俯卧撑下挂 1 道题
 * </pre>
 *
 * <p>保存是<b>全量覆盖</b>（先删后插）——未提交的节点视为「这个节点不挂题」。</p>
 */
@Data
@Schema(description = "保存节点-题目关联请求")
public class CataSubjectSaveDTO {

    @Schema(description = "目录节点id（训练动作 / 测评）", example = "1001")
    @NotNull(message = "目录节点id不能为空")
    private Long cataId;

    @Schema(description = "该节点下的题目id列表（按数组顺序决定题目顺序）", example = "[1, 2]")
    @NotNull(message = "题目id列表不能为空")
    @Size(min = 1, message = "每个节点至少要有一道题")
    private List<Long> subjectIds;
}
