package com.fitmind.course.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.course.domain.dto.CourseBaseInfoSaveDTO;
import com.fitmind.course.domain.po.CourseDraft;
import com.fitmind.course.domain.query.CoursePageQuery;
import com.fitmind.course.domain.vo.CourseBaseInfoVO;
import com.fitmind.course.domain.vo.CoursePageVO;
import com.fitmind.course.domain.vo.CourseSaveVO;
import com.fitmind.course.domain.vo.NameExistVO;

/**
 * 课程草稿服务 —— 「草稿-发布」机制的核心。
 *
 * <p>教练的所有编辑都发生在草稿表上，点上架才整体同步到正式表。
 * 这样线上正在学的学员看到的内容不会被编辑过程影响。</p>
 */
public interface ICourseDraftService extends IService<CourseDraft> {

    /**
     * 保存课程基本信息（含正文），同时推进 step。
     *
     * @return 课程 id 与当前 step
     */
    CourseSaveVO save(CourseBaseInfoSaveDTO dto);

    /** 查询课程基本信息（优先取草稿，草稿没有则取正式表） */
    CourseBaseInfoVO getCourseBaseInfo(Long id);

    /**
     * 推进课程填写进度。
     *
     * <p><b>只能前进，不能后退</b>——教练回来改一下基本信息，不该把已填好的
     * 目录、视频进度重置掉。走到「目录」「习题」步时顺带刷新课时数。</p>
     *
     * @param id   课程 id
     * @param step 目标步骤
     */
    void updateStep(Long id, Integer step);

    /**
     * 上架：把草稿整体同步到正式表，然后删除草稿，并发 MQ 通知搜索服务。
     */
    void upShelf(Long id);

    /** 上架前校验（不落库，供前端提前提示） */
    void checkBeforeUpShelf(Long id);

    /**
     * 下架：正式表状态改为下架，并把数据反向复制回草稿表供后续编辑。
     */
    void downShelf(Long id);

    /** 分页查询草稿表（待上架 / 已下架的课程） */
    PageDTO<CoursePageVO> queryForPage(CoursePageQuery query);

    /** 课程名称是否已存在 */
    NameExistVO checkName(String name, Long id);

    /** 删除课程（草稿 + 正式一起删） */
    void delete(Long id);
}
