package com.fitmind.plan.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量添加训练日入参。
 *
 * <p>一次选周一~周五就一次提交（不是循环调五次）：循环调用会产生 5 个事务，
 * 第 4 个失败时前 3 个已经落库，用户看到"添加失败"但列表里多了 3 天。</p>
 */
@Data
@Schema(description = "批量添加训练日入参")
public class PlanDayCreateDTO {

    @Schema(description = "星期列表，1=周一 … 7=周日", example = "[1,2,3,4,5]")
    @NotEmpty(message = "至少选一天（周一~周日）")
    @Size(max = 7, message = "一周最多 7 天")
    private List<Integer> weekdays;
}
