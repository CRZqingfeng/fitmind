package com.fitmind.course.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 保存课程返回结果。
 *
 * <p>把课程 id 和当前填写进度一起返回，前端据此决定下一步跳到哪个表单。</p>
 */
@Data
@Schema(description = "保存课程返回结果")
public class CourseSaveVO {

    @Schema(description = "课程id")
    private Long id;

    @Schema(description = "课程名称")
    private String name;

    @Schema(description = "当前填写进度：1-基本信息 2-目录 3-视频 4-习题 5-教练")
    private Integer step;

    public static CourseSaveVO of(Long id, String name, Integer step) {
        CourseSaveVO vo = new CourseSaveVO();
        vo.setId(id);
        vo.setName(name);
        vo.setStep(step);
        return vo;
    }
}
