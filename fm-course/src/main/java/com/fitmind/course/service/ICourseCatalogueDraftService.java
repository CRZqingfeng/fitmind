package com.fitmind.course.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.course.domain.dto.CataSaveDTO;
import com.fitmind.course.domain.dto.CourseMediaDTO;
import com.fitmind.course.domain.po.CourseCatalogueDraft;
import com.fitmind.course.domain.vo.CataVO;

import java.util.List;
import java.util.Map;

/**
 * 训练目录服务（章=训练日，节=训练动作）。
 *
 * <p>这是课程模块里最复杂的一块，两个关键设计：</p>
 * <ol>
 *   <li><b>保存是全量覆盖（先删后插）</b>，但要靠内存 map 保住 mediaId 等业务字段——
 *       因为接口提交的只有目录结构，没有 mediaId</li>
 *   <li><b>已上架的结构受保护</b>：训练日不能删、序号不能改（学员可能正在学）</li>
 * </ol>
 */
public interface ICourseCatalogueDraftService extends IService<CourseCatalogueDraft> {

    /**
     * 保存训练目录（全量覆盖）。
     *
     * @param courseId 课程 id
     * @param dtos     目录树（训练日 + 其下的训练动作）
     * @param step     当前处于哪一步（CATALOGUE 或 SUBJECT），决定删除哪些类型的旧目录
     */
    void save(Long courseId, List<CataSaveDTO> dtos, Integer step);

    /**
     * 查询训练目录（树形）。
     *
     * @param see true-优先看已上架的目录（用户端），false-只看草稿（管理端编辑）
     */
    List<CataVO> queryCourseCatalogues(Long courseId, Boolean see);

    /**
     * 保存训练动作信息（绑定视频 + 设置动作参数），并重算训练日时长。
     */
    void saveMediaInfo(Long courseId, List<CourseMediaDTO> dtos);

    /** 上架前校验：每个训练动作都必须绑定视频 */
    void checkCataInfoImplated(Long courseId);

    /** 上架：草稿目录同步到正式表，然后删草稿 */
    void copyToShelf(Long courseId, Boolean isFirstShelf);

    /** 下架：正式表目录反向复制回草稿 */
    void copyFromShelf(Long courseId);

    /**
     * 计算每个训练日的视频总时长。
     *
     * @return key = 训练日 id，value = 该天下所有动作的时长之和
     */
    Map<Long, Integer> calculateMediaDuration(Long courseId);

    /** 统计训练动作数量 */
    Integer totalSectionNums(Long courseId);

    /** 查询某课程指定类型的目录 id 列表 */
    List<Long> queryCataIdsOfCourse(Long courseId, List<Integer> types);

    /**
     * 删除课程时清理训练目录（草稿 + 正式）。
     *
     * <p>目录表只有 course_id 一个锚点，课程删了它们就是孤儿数据。</p>
     */
    void deleteByCourseId(Long courseId);
}
