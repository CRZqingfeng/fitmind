package com.fitmind.course.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.course.domain.dto.CataSubjectSaveDTO;
import com.fitmind.course.domain.po.CourseCataSubjectDraft;
import com.fitmind.course.domain.vo.CataSubjectVO;

import java.util.List;
import java.util.Map;

/**
 * 训练节点 ↔ 题目 关联服务。
 *
 * <p>对应常见实现的 {@code ICourseCataSubjectDraftService}，但我们的职责更完整：
 * 常见实现那个接口只有一个 {@code deleteNotInCataIdList} 方法（题目关联的保存散落在
 * 「目录服务」里，上架时跨服务写进 exam），我们把保存 / 查询 / 上下架同步都收在这里——
 * 一个业务对象的操作应该待在一起。</p>
 *
 * <p>草稿-发布语义与目录、教练完全一致：编辑改草稿，上架时草稿 → 正式，
 * 下架时正式 → 草稿。</p>
 */
public interface ICourseCataSubjectService extends IService<CourseCataSubjectDraft> {

    /**
     * 保存节点-题目关联（<b>全量覆盖</b>）。
     *
     * <p>未提交的节点视为「这个节点不挂题」，它的旧关联会被清掉。</p>
     */
    void save(Long courseId, List<CataSubjectSaveDTO> dtos);

    /**
     * 查询课程下各节点的题目（编辑页回显）。
     *
     * @param see true-优先看已上架（用户端），false-只看草稿（管理端编辑）
     */
    List<CataSubjectVO> queryCataSubjects(Long courseId, Boolean see);

    /**
     * 清理「已经被删掉的目录节点」上挂的关联。
     *
     * <p>教练在目录里删掉一个训练动作后调用——否则它下面的题目关联会变成孤儿，
     * 没有课程id之外的锚点，留着只会在管理端查询时冒出来。</p>
     */
    void deleteNotInCataIdList(Long courseId);

    /**
     * 统计每个目录节点下有几道题（给目录树补 {@code subjectNum}）。
     *
     * @param fromShelf 题目数来自正式表（true）还是草稿表（false）——
     *                  必须和目录树本身取数的那张表保持一致，否则编辑期会显示上架前的旧数量
     */
    Map<Long, Integer> countByCataIds(List<Long> cataIds, Boolean fromShelf);

    /** 上架：草稿关联全量同步到正式表，然后删草稿 */
    void copyToShelf(Long courseId);

    /** 下架：正式表关联反向复制回草稿表 */
    void copyFromShelf(Long courseId);

    /** 删除课程时清理（草稿 + 正式一起删） */
    void deleteByCourseId(Long courseId);
}
