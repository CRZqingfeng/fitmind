package com.fitmind.api.dto.course;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 训练目录简要信息（训练日 / 训练动作）。
 *
 * <p>管理端的问答、笔记列表要展示「哪个训练日的哪个动作」，
 * 但一次查询涉及几十个目录 id —— 逐个查课程全文是 N+1 次跨服务调用，所以单独开批量接口。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor(staticName = "of")
@Schema(description = "训练目录简要信息")
public class CataSimpleDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "目录id（训练日id 或 训练动作id）")
    private Long id;

    @Schema(description = "目录名称（训练日名 / 动作名）")
    private String name;

    @Schema(description = "目录类型：1-训练日 2-训练动作")
    private Integer type;
}
