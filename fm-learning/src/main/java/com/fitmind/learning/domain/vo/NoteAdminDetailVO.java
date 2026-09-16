package com.fitmind.learning.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 笔记详情（管理端）。
 *
 * <p>相比列表多两样：作者手机号（便于联系）与<b>采集者名单</b>。</p>
 */
@Data
@Schema(description = "管理端笔记详情")
public class NoteAdminDetailVO extends NoteAdminVO {

    @Schema(description = "作者手机号")
    private String authorPhone;

    @Schema(description = "采集过这条笔记的用户姓名列表")
    private List<String> gathers;
}
