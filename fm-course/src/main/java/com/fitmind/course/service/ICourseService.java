package com.fitmind.course.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.api.dto.course.CourseFullDTO;
import com.fitmind.api.dto.course.CataSimpleDTO;
import com.fitmind.api.dto.course.CourseSimpleDTO;
import com.fitmind.api.dto.course.CourseSearchInfoDTO;
import com.fitmind.common.domain.dto.PageDTO;

import java.util.List;
import com.fitmind.course.domain.po.Course;
import com.fitmind.course.domain.query.CoursePageQuery;
import com.fitmind.course.domain.vo.CoursePageVO;

/**
 * 课程（正式表）服务。
 *
 * <p>只负责正式表 {@code course} 的读写——已上架 / 已完结的课程数据在这里。
 * 编辑态的课程在 {@link ICourseDraftService} 那边。</p>
 */
public interface ICourseService extends IService<Course> {

    /** 分页查询正式表课程（已上架 / 已完结） */
    PageDTO<CoursePageVO> queryForPage(CoursePageQuery query);

    /** 更新课程状态 */
    void updateStatus(Long id, Integer status);

    /**
     * 查询课程检索信息（供 fm-search 组装 ES 文档）。
     *
     * <p>为什么由 fm-course 聚合而不是让 fm-search 到处调：
     * 组装一份 ES 文档要课程 + 分类 + 教练 + 训练目录四份数据，
     * 如果 fm-search 自己拼，一次索引同步会变成 4~5 次跨服务调用。
     * 让数据的归属方一次性准备好，调用方只负责"翻译成 ES 文档"。</p>
     *
     * @param id 课程id
     * @return 检索信息；课程不存在时返回 null
     */
    CourseSearchInfoDTO querySearchInfo(Long id);

    /**
     * 查询课程全文（供 fm-learning 报名校验 / 学习进度计算使用）。
     *
     * @param id             课程id
     * @param withCatalogues 是否带训练目录（报名只要基本信息，进度计算要目录）
     * @return 课程全文；课程不存在时返回 null
     */
    CourseFullDTO queryCourseFull(Long id, boolean withCatalogues);

    /**
     * 批量查询课程简要信息（供 fm-learning 课表分页 / 计划列表，避免 N+1 单查）。
     *
     * @param ids 课程id 集合
     * @return 简要信息列表
     */
    List<CourseSimpleDTO> querySimpleList(List<Long> ids);

    /**
     * 批量查询训练目录名（供 fm-learning 问答/笔记管理端展示，避免 N+1 单查）。
     *
     * @param ids 目录id 集合
     * @return 简要信息列表
     */
    List<CataSimpleDTO> queryCataSimpleList(List<Long> ids);
}
