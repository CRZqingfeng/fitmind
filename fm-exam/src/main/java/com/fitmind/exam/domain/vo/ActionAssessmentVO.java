package com.fitmind.exam.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 动作考核视图。
 */
@Data
@Accessors(chain = true)
@Schema(description = "动作考核")
public class ActionAssessmentVO {

    @Schema(description = "记录id")
    private Long id;

    private Long userId;

    @Schema(description = "学员名（管理端列表用）")
    private String userName;

    private Long courseId;

    @Schema(description = "训练动作名（管理端列表用）")
    private String cataName;

    private Long cataId;

    /**
     * ★★ 媒资 id（M7-10 补）。
     *
     * <p>为什么要单独给 mediaId，而不是直接用 {@link #videoUrl}：播放地址是**带签名的临时地址**、
     * 会过期 —— 存库没有意义（存下去几分钟后就失效了）。前端拿 mediaId **现取一次** play-url
     * 才拿得到有效地址。</p>
     *
     * <p>⚠️ 补它是因为实测发现：`videoUrl` 字段从来**没有被赋过值**（提交时只写了 mediaId），
     * 而接口却一直把它透传出去 —— 结果是<b>学员和教练都看不到视频，且不报任何错</b>。
     * 这类"字段一直在、但永远是 null"的问题，只有真的点开页面（或断言它非空）才会暴露。</p>
     */
    private Long mediaId;

    private String videoUrl;

    private String remark;

    @Schema(description = "状态：1-待评分 2-已评分")
    private Integer status;

    @Schema(description = "状态文案")
    private String statusDesc;

    @Schema(description = "教练评分 0-100")
    private Integer score;

    private String comment;

    private Long coachId;

    private LocalDateTime submitTime;

    private LocalDateTime reviewTime;
}
