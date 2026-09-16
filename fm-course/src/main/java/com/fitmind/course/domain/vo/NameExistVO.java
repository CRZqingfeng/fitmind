package com.fitmind.course.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 名称是否已存在。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "名称是否已存在")
public class NameExistVO {

    @Schema(description = "是否已存在")
    private Boolean exist;

    public static NameExistVO of(boolean exist) {
        return new NameExistVO(exist);
    }
}
