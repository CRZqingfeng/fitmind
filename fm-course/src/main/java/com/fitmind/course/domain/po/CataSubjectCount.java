package com.fitmind.course.domain.po;

import lombok.Data;

/**
 * 「某个目录节点下有几道题」的统计结果。
 *
 * <p>不是数据库表，是 {@code GROUP BY cata_id} 查询的映射载体。</p>
 *
 * <p>为什么要单独批量统计：训练目录树可能是「8 个训练日 × 6 个动作」，
 * 逐个节点查题目数就是 48 次查询（N+1）。一次 {@code GROUP BY} 拿全，
 * 再在内存里查 map。</p>
 */
@Data
public class CataSubjectCount {

    private Long cataId;

    private Integer num;
}
