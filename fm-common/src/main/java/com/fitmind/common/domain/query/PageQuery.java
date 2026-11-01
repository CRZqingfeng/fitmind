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

    /**
     * 页码 —— **null 时回落默认值**（《待修复问题清单》#A28 的第二条）。
     *
     * <p>★ 为什么要自己写 getter（Lombok 已经会生成一个）：字段上写了
     * {@code private Integer pageNo = 1} 也没用 —— 前端只要传一个**空值**
     * （`?pageNo=`，比如某个筛选表单清空后仍带着参数），Spring 的 WebDataBinder
     * 会把空串转成 {@code null}，**直接覆盖掉字段默认值**。
     * 之后 {@code from()} 里 `(pageNo - 1)` 自动拆箱 → NPE →
     * 被全局兜底成 500「系统繁忙」。实测：`/pls/exercises?pageNo=` 曾经就是 500。</p>
     *
     * <p>放在 getter 这一层兜底，是为了让<b>所有</b>调用方（`from()`、`toMpPage()`
     * 以及 16 个服务里各自的用法）不用各写一遍判空。</p>
     */
    public Integer getPageNo() {
        return pageNo == null ? DEFAULT_PAGE_NUM : pageNo;
    }

    /**
     * 每页大小 —— null 时回落默认值（理由同 {@link #getPageNo()}）。
     *
     * <p>★ <b>只兜 null，不兜 0/负数</b>：本项目里 {@code pageSize=0} 在别处有语义
     * （{@code fm-search} 用它表示"不算总页数"），顺手 coerce 会悄悄改掉那个行为。
     * 修 bug 时不要捎带改语义 —— 那是另一件事。</p>
     */
    public Integer getPageSize() {
        return pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
    }

    /** 分页起始位置（手写 SQL 的 limit 用） */
    public int from() {
        return (getPageNo() - 1) * getPageSize();
    }

    /**
     * 转换成 MyBatis-Plus 分页对象。
     *
     * @param orderItems 排序项；传了就以它为准，忽略 sortBy
     */
    @SuppressWarnings("unchecked")
    public <T> Page<T> toMpPage(OrderItem... orderItems) {
        // ★ 用 getter 而不是字段：空值回落默认值（见 getPageNo 的注释）
        Page<T> page = new Page<>(getPageNo(), getPageSize());
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
        Page<T> page = new Page<>(getPageNo(), getPageSize());
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
