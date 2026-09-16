package com.fitmind.common.domain.query;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitmind.common.constants.Constant;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 分页请求参数。
 *
 * <p>所有需要分页的查询对象继承它即可。核心能力是把自己转成 MyBatis-Plus 的
 * {@link Page} 对象（{@link #toMpPage}），省掉每个 Service 里重复拼分页代码。</p>
 *
 * <pre>{@code
 * // 用法一：默认按创建时间倒序
 * Page<Media> p = query.toMpPageDefaultSortByCreateTimeDesc();
 *
 * // 用法二：指定默认排序
 * Page<Media> p = query.toMpPage("create_time", false);
 * }</pre>
 */
@Data
@Accessors(chain = true)
@Schema(description = "分页请求参数")
public class PageQuery {

    /** 默认每页大小 */
    public static final Integer DEFAULT_PAGE_SIZE = 20;
    /** 默认页码 */
    public static final Integer DEFAULT_PAGE_NUM = 1;

    @Schema(description = "页码", example = "1")
    @Min(value = 1, message = "页码不能小于1")
    private Integer pageNo = DEFAULT_PAGE_NUM;

    @Schema(description = "每页大小", example = "20")
    @Min(value = 1, message = "每页查询数量不能小于1")
    private Integer pageSize = DEFAULT_PAGE_SIZE;

    @Schema(description = "是否升序", example = "true")
    private Boolean isAsc = true;

    @Schema(description = "排序字段", example = "id")
    private String sortBy;

    /** 分页起始位置（手写 SQL 的 limit 用） */
    public int from() {
        return (pageNo - 1) * pageSize;
    }

    /**
     * 转换成 MyBatis-Plus 分页对象。
     *
     * @param orderItems 排序项；传了就以它为准，忽略 sortBy
     */
    @SuppressWarnings("unchecked")
    public <T> Page<T> toMpPage(OrderItem... orderItems) {
        Page<T> page = new Page<>(pageNo, pageSize);
        // 1. 手动指定了排序方式，以手动的为准
        if (orderItems != null && orderItems.length > 0) {
            for (OrderItem orderItem : orderItems) {
                page.addOrder(orderItem);
            }
            return page;
        }
        // 2. 前端传了排序字段
        if (isNotBlank(sortBy)) {
            OrderItem orderItem = new OrderItem();
            orderItem.setAsc(isAsc);
            orderItem.setColumn(sortBy);
            page.addOrder(orderItem);
        }
        return page;
    }

    /**
     * 转换成 MyBatis-Plus 分页对象，前端没传排序字段时用给定的默认排序。
     *
     * @param defaultSortBy 默认排序字段（数据库列名，如 create_time）
     * @param isAsc         默认是否升序
     */
    public <T> Page<T> toMpPage(String defaultSortBy, boolean isAsc) {
        if (!isNotBlank(sortBy)) {
            sortBy = defaultSortBy;
            this.isAsc = isAsc;
        }
        Page<T> page = new Page<>(pageNo, pageSize);
        OrderItem orderItem = new OrderItem();
        orderItem.setAsc(this.isAsc);
        orderItem.setColumn(sortBy);
        page.addOrder(orderItem);
        return page;
    }

    /** 默认按创建时间倒序（列表页最常见需求） */
    public <T> Page<T> toMpPageDefaultSortByCreateTimeDesc() {
        return toMpPage(Constant.DATA_FIELD_NAME_CREATE_TIME, false);
    }

    /** 判空：不引第三方工具包，避免给公共模块加依赖 */
    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
