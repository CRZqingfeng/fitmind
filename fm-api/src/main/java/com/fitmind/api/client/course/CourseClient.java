package com.fitmind.api.client.course;

import com.fitmind.api.client.course.fallback.CourseClientFallback;
import com.fitmind.api.dto.course.CourseFullDTO;
import com.fitmind.api.dto.course.CataSimpleDTO;
import com.fitmind.api.dto.course.CourseSimpleDTO;
import com.fitmind.api.dto.course.CourseSearchInfoDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 课程服务 Feign 客户端。
 *
 * <p>供 fm-search 在收到「课程上架」消息后拉取完整课程数据，用于组装 ES 文档。</p>
 *
 * <p>返回类型是领域对象而非 {@code R}——服务间调用不带
 * {@code request-from: gateway} 头，不会被包装。</p>
 */
@FeignClient(value = "fm-course", fallbackFactory = CourseClientFallback.class)
public interface CourseClient {

    /**
     * 查询课程检索信息（含分类名 / 教练 / 训练动作）。
     *
     * @param id 课程id
     * @return 检索信息；课程不存在时返回 null
     */
    @GetMapping("/courses/internal/{id}/searchInfo")
    CourseSearchInfoDTO querySearchInfo(@PathVariable("id") Long id);

    /**
     * 查询课程全文（供 fm-learning 报名校验 / 学习进度计算使用）。
     *
     * @param id             课程id
     * @param withCatalogues 是否带训练目录（报名只要基本信息，进度计算要目录）
     * @return 课程全文；课程不存在时返回 null
     */
    @GetMapping("/courses/internal/{id}/full")
    CourseFullDTO queryCourseFull(@PathVariable("id") Long id,
                                  @RequestParam("withCatalogues") boolean withCatalogues);

    /**
     * 批量查询课程简要信息（课表分页 / 计划列表用，避免 N+1 次单查）。
     *
     * @param ids 课程id 集合
     * @return 简要信息列表（保持入参顺序无关；不存在的 id 不出现在结果里）
     */
    @GetMapping("/courses/internal/simple")
    List<CourseSimpleDTO> queryCourseSimpleList(@RequestParam("ids") List<Long> ids);

    /**
     * 批量查询训练目录名（训练日名 / 动作名）。
     *
     * <p>管理端的问答、笔记列表要展示"哪个训练日的哪个动作"，一次查询涉及几十个目录 id，
     * 逐个查课程全文就是 N+1 次跨服务调用。</p>
     *
     * @param ids 目录id 集合（训练日id / 训练动作id 混在一起传即可）
     * @return 简要信息列表（不存在的 id 不出现在结果里）
     */
    @GetMapping("/courses/internal/catas/simple")
    List<CataSimpleDTO> queryCataSimpleList(@RequestParam("ids") List<Long> ids);
}
