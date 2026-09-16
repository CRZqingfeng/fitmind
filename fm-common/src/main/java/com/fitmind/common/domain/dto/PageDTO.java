package com.fitmind.common.domain.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 分页结果。
 *
 * <p>Service 查完 MyBatis-Plus 的 {@link Page} 后，用它转成对外的 DTO，
 * 只暴露 total / pages / list 三个字段，不把 MP 的分页对象泄漏到接口层。</p>
 *
 * <pre>{@code
 * // 直接返回 PO 分页
 * return PageDTO.of(mediaPage);
 *
 * // 需要做 PO -> VO 转换
 * return PageDTO.of(mediaPage, m -> BeanUtil.copyProperties(m, MediaVO.class));
 * }</pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "分页结果")
public class PageDTO<T> {

    @Schema(description = "总条数")
    protected Long total;

    @Schema(description = "总页码数")
    protected Long pages;

    @Schema(description = "当前页数据")
    protected List<T> list;

    /** 空结果（指定了总数和页数） */
    public static <T> PageDTO<T> empty(Long total, Long pages) {
        return new PageDTO<>(total, pages, new ArrayList<>());
    }

    /** 空结果（从 MP 分页对象取总数和页数） */
    public static <T> PageDTO<T> empty(Page<?> page) {
        return new PageDTO<>(page.getTotal(), page.getPages(), new ArrayList<>());
    }

    /** 从 MP 分页对象构建，数据类型不变 */
    public static <T> PageDTO<T> of(Page<T> page) {
        if (page == null) {
            return new PageDTO<>();
        }
        if (isEmpty(page.getRecords())) {
            return empty(page);
        }
        return new PageDTO<>(page.getTotal(), page.getPages(), page.getRecords());
    }

    /**
     * 从 MP 分页对象构建，并做类型转换。
     *
     * @param mapper PO -> VO 的转换函数
     */
    public static <T, R> PageDTO<T> of(Page<R> page, Function<R, T> mapper) {
        if (page == null) {
            return new PageDTO<>();
        }
        if (isEmpty(page.getRecords())) {
            return empty(page);
        }
        List<T> list = page.getRecords().stream().map(mapper).toList();
        return new PageDTO<>(page.getTotal(), page.getPages(), list);
    }

    /** 用已有的列表 + 分页信息构建（自己查 list、自己 count 的场景） */
    public static <T> PageDTO<T> of(Page<?> page, List<T> list) {
        return new PageDTO<>(page.getTotal(), page.getPages(), list);
    }

    /** 结果是否为空 */
    @JsonIgnore
    @Schema(hidden = true)
    public boolean isEmpty() {
        return list == null || list.isEmpty();
    }

    private static boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

    /** 兜底：避免 NPE 时返回 null 列表 */
    public List<T> getList() {
        return list == null ? Collections.emptyList() : list;
    }
}
