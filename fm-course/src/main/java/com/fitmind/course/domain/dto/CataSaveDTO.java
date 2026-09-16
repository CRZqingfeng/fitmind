package com.fitmind.course.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 保存训练目录的请求（递归结构）。
 *
 * <p>提交时是「训练日 + 它下面的动作」两层：</p>
 * <pre>
 *   [{ id, type=1, name="Day 1 胸部", index=1,
 *      sections: [ {id, type=2, name="哑铃卧推"}, {id, type=2, name="俯卧撑"} ] }]
 * </pre>
 *
 * <p>⚠️ 注意这里<b>只提交目录结构</b>（id/名称/序号/类型），
 * 不带 mediaId 和动作参数——那些由「保存动作信息」接口单独提交。
 * 服务端保存时会去内存 map 里捞老对象复用，把 mediaId 保留下来。</p>
 */
@Data
@Schema(description = "保存训练目录请求")
public class CataSaveDTO {

    @Schema(description = "目录id；不传表示新增（服务端生成）")
    private Long id;

    @Schema(description = "类型：1-训练日 2-训练动作", example = "1")
    @NotNull(message = "目录类型不能为空")
    private Integer type;

    @Schema(description = "名称：训练日名 / 动作名", example = "Day 1 胸部+三头")
    @NotBlank(message = "目录名称不能为空")
    private String name;

    @Schema(description = "排序序号，训练日从 1 开始连续递增", example = "1")
    @NotNull(message = "序号不能为空")
    private Integer index;

    @Schema(description = "子目录（训练日下面的训练动作）")
    private List<CataSaveDTO> sections;
}
