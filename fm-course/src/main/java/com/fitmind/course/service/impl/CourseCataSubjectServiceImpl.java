package com.fitmind.course.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.course.constants.CourseConstants;
import com.fitmind.course.constants.CourseErrorInfo;
import com.fitmind.course.domain.dto.CataSubjectSaveDTO;
import com.fitmind.course.domain.po.CataSubjectCount;
import com.fitmind.course.domain.po.CourseCatalogue;
import com.fitmind.course.domain.po.CourseCatalogueDraft;
import com.fitmind.course.domain.po.CourseCataSubject;
import com.fitmind.course.domain.po.CourseCataSubjectDraft;
import com.fitmind.course.domain.po.Subject;
import com.fitmind.course.domain.vo.CataSubjectVO;
import com.fitmind.course.mapper.CourseCatalogueDraftMapper;
import com.fitmind.course.mapper.CourseCatalogueMapper;
import com.fitmind.course.mapper.CourseCataSubjectDraftMapper;
import com.fitmind.course.mapper.CourseCataSubjectMapper;
import com.fitmind.course.mapper.SubjectMapper;
import com.fitmind.course.service.ICourseCataSubjectService;
import com.fitmind.course.service.ICourseDraftService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 训练节点 ↔ 题目 关联服务实现。
 */
@Slf4j
@Service
public class CourseCataSubjectServiceImpl
        extends ServiceImpl<CourseCataSubjectDraftMapper, CourseCataSubjectDraft>
        implements ICourseCataSubjectService {

    private static final int TYPE_SECTION = CourseConstants.CataType.SECTION;
    private static final int TYPE_PRACTICE = CourseConstants.CataType.PRACTICE;

    private final ICourseDraftService courseDraftService;
    private final CourseCatalogueDraftMapper courseCatalogueDraftMapper;
    private final CourseCatalogueMapper courseCatalogueMapper;
    private final CourseCataSubjectMapper courseCataSubjectMapper;
    private final SubjectMapper subjectMapper;

    /**
     * ⚠️ 这里注的是 <b>Mapper</b> 而不是 {@code ICourseCatalogueDraftService}：
     * 目录服务保存时会反向调本服务的 {@code deleteNotInCataIdList}，
     * 如果本服务再依赖目录服务，就是一条 Bean 循环依赖链。
     * 直接查表没有这个问题，也少一层间接。
     */
    public CourseCataSubjectServiceImpl(ICourseDraftService courseDraftService,
                                        CourseCatalogueDraftMapper courseCatalogueDraftMapper,
                                        CourseCatalogueMapper courseCatalogueMapper,
                                        CourseCataSubjectMapper courseCataSubjectMapper,
                                        SubjectMapper subjectMapper) {
        this.courseDraftService = courseDraftService;
        this.courseCatalogueDraftMapper = courseCatalogueDraftMapper;
        this.courseCatalogueMapper = courseCatalogueMapper;
        this.courseCataSubjectMapper = courseCataSubjectMapper;
        this.subjectMapper = subjectMapper;
    }

    // ==================== 保存 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(Long courseId, List<CataSubjectSaveDTO> dtos) {
        // 1. 课程必须存在（且要有草稿——只有草稿态才能编辑题目）
        if (courseDraftService.getById(courseId) == null) {
            throw new BizIllegalException(CourseErrorInfo.Msg.SUBJECT_SAVE_BEFORE_COURSE);
        }
        if (CollectionUtils.isEmpty(dtos)) {
            // 传空数组 = 该课程不挂任何题，直接清空即可
            baseMapper.deleteByCourseId(courseId);
            return;
        }

        // 2. 同一个节点不能提交两次（否则 c_index 会重叠，顺序变得不确定）
        Set<Long> cataIdSet = dtos.stream().map(CataSubjectSaveDTO::getCataId).collect(Collectors.toSet());
        if (cataIdSet.size() < dtos.size()) {
            throw new BizIllegalException(CourseErrorInfo.Msg.CATA_SUBJECT_REPEAT);
        }

        // 3. 节点必须属于本课程，且只能是「训练动作」或「测评」
        List<CourseCatalogueDraft> catas = courseCatalogueDraftMapper.selectList(
                new LambdaQueryWrapper<CourseCatalogueDraft>()
                        .eq(CourseCatalogueDraft::getCourseId, courseId)
                        .in(CourseCatalogueDraft::getId, cataIdSet));
        if (catas.size() != cataIdSet.size()) {
            throw new BizIllegalException(CourseErrorInfo.Msg.CATA_SUBJECT_NOT_IN_COURSE);
        }
        for (CourseCatalogueDraft c : catas) {
            if (c.getType() == null || (c.getType() != TYPE_SECTION && c.getType() != TYPE_PRACTICE)) {
                throw new BizIllegalException(CourseErrorInfo.Msg.CATA_SUBJECT_TYPE_ILLEGAL);
            }
        }

        // 4. 题目必须属于本课程（防止把别的课程的题目挂过来）
        List<Long> subjectIds = dtos.stream()
                .flatMap(d -> d.getSubjectIds().stream())
                .distinct()
                .collect(Collectors.toList());
        if (subjectIds.isEmpty()) {
            throw new BizIllegalException(CourseErrorInfo.Msg.CATA_SUBJECT_EMPTY);
        }
        List<Subject> subjects = subjectMapper.selectList(new LambdaQueryWrapper<Subject>()
                .eq(Subject::getCourseId, courseId)
                .in(Subject::getId, subjectIds));
        if (subjects.size() != subjectIds.size()) {
            throw new BizIllegalException(CourseErrorInfo.Msg.CATA_SUBJECT_SUBJECT_NOT_IN_COURSE);
        }

        // 5. 全量覆盖：先删后插
        baseMapper.deleteByCourseId(courseId);
        List<CourseCataSubjectDraft> drafts = new ArrayList<>();
        for (CataSubjectSaveDTO dto : dtos) {
            int index = 1;
            for (Long subjectId : dto.getSubjectIds()) {
                drafts.add(new CourseCataSubjectDraft()
                        .setId(IdUtil.getSnowflakeNextId())
                        .setCourseId(courseId)
                        .setCataId(dto.getCataId())
                        .setSubjectId(subjectId)
                        .setCIndex(index++));
            }
        }
        saveBatch(drafts);

        // 6. 推进填写进度到「习题」。
        //    updateStep 内部是 Math.max，所以「先出题后配教练」也不会把进度打回去；
        //    课程信息不全照样不能上架（上架门槛是 TEACHER）。
        courseDraftService.updateStep(courseId, CourseConstants.CourseStep.SUBJECT);
        log.info("保存节点-题目关联：courseId={}, 节点 {} 个, 关联 {} 条",
                courseId, dtos.size(), drafts.size());
    }

    // ==================== 查询 ====================

    @Override
    public List<CataSubjectVO> queryCataSubjects(Long courseId, Boolean see) {
        List<CourseCataSubjectDraft> relations = loadRelations(courseId, see);
        if (CollectionUtils.isEmpty(relations)) {
            return new ArrayList<>();
        }
        // 题目信息（教师端回显要题干，不需要答案，所以只查基本信息）
        List<Long> subjectIds = relations.stream()
                .map(CourseCataSubjectDraft::getSubjectId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, Subject> subjectMap = subjectMapper
                .selectList(new LambdaQueryWrapper<Subject>().in(Subject::getId, subjectIds))
                .stream().collect(Collectors.toMap(Subject::getId, Function.identity(), (a, b) -> a));

        // 节点名称
        Map<Long, String> cataNameMap = queryCataNames(courseId, see);

        // 按 cataId 分组，组内按 c_index 排序
        Map<Long, List<CourseCataSubjectDraft>> grouped = relations.stream()
                .collect(Collectors.groupingBy(CourseCataSubjectDraft::getCataId,
                        LinkedHashMap::new, Collectors.toList()));

        List<CataSubjectVO> result = new ArrayList<>();
        for (Map.Entry<Long, List<CourseCataSubjectDraft>> entry : grouped.entrySet()) {
            List<CataSubjectVO.SubjectBriefVO> briefs = entry.getValue().stream()
                    .sorted(Comparator.comparing(CourseCataSubjectDraft::getCIndex,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(r -> subjectMap.get(r.getSubjectId()))
                    .filter(java.util.Objects::nonNull)
                    .map(s -> {
                        CataSubjectVO.SubjectBriefVO brief = new CataSubjectVO.SubjectBriefVO();
                        brief.setId(s.getId());
                        brief.setName(s.getName());
                        brief.setSubjectType(s.getSubjectType());
                        brief.setDifficulty(s.getDifficulty());
                        return brief;
                    })
                    .collect(Collectors.toList());
            CataSubjectVO vo = new CataSubjectVO();
            vo.setCataId(entry.getKey());
            vo.setCataName(cataNameMap.get(entry.getKey()));
            vo.setSubjects(briefs);
            result.add(vo);
        }
        return result;
    }

    @Override
    public void deleteNotInCataIdList(Long courseId) {
        // 当前课程仍然存在的节点（草稿表刚被保存过，以它为准）
        List<Long> cataIds = courseCatalogueDraftMapper.getByCourseId(courseId).stream()
                .map(CourseCatalogueDraft::getId)
                .collect(Collectors.toList());
        int n = baseMapper.deleteNotInCataIdList(courseId, cataIds);
        if (n > 0) {
            log.info("清理失效的题目关联：courseId={}, 共 {} 条（对应的目录节点已被删除）", courseId, n);
        }
    }

    @Override
    public Map<Long, Integer> countByCataIds(List<Long> cataIds, Boolean fromShelf) {
        if (CollectionUtils.isEmpty(cataIds)) {
            return new HashMap<>();
        }
        List<CataSubjectCount> counts = Boolean.TRUE.equals(fromShelf)
                ? courseCataSubjectMapper.countByCataIds(cataIds)
                : baseMapper.countByCataIds(cataIds);
        return counts.stream().collect(Collectors.toMap(
                CataSubjectCount::getCataId,
                c -> c.getNum() == null ? 0 : c.getNum(),
                (a, b) -> a));
    }

    /** 取关联数据：用户端优先看已上架，管理端只看草稿 */
    private List<CourseCataSubjectDraft> loadRelations(Long courseId, Boolean see) {
        if (Boolean.TRUE.equals(see)) {
            List<CourseCataSubject> shelf = courseCataSubjectMapper.getByCourseId(courseId);
            if (!CollectionUtils.isEmpty(shelf)) {
                return shelf.stream()
                        .map(s -> BeanUtil.copyProperties(s, CourseCataSubjectDraft.class))
                        .collect(Collectors.toList());
            }
        }
        return baseMapper.getByCourseId(courseId);
    }

    /** 取节点名称（动作名 / 测评名） */
    private Map<Long, String> queryCataNames(Long courseId, Boolean see) {
        if (Boolean.TRUE.equals(see)) {
            List<CourseCatalogue> shelf = courseCatalogueMapper.selectList(
                    new LambdaQueryWrapper<CourseCatalogue>().eq(CourseCatalogue::getCourseId, courseId));
            if (!CollectionUtils.isEmpty(shelf)) {
                return shelf.stream().collect(Collectors.toMap(
                        CourseCatalogue::getId, CourseCatalogue::getName, (a, b) -> a));
            }
        }
        return courseCatalogueDraftMapper.getByCourseId(courseId).stream().collect(Collectors.toMap(
                CourseCatalogueDraft::getId, CourseCatalogueDraft::getName, (a, b) -> a));
    }

    // ==================== 草稿-发布 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void copyToShelf(Long courseId) {
        List<CourseCataSubjectDraft> drafts = baseMapper.getByCourseId(courseId);
        // 先清空正式表：只做 saveOrUpdate 的话，本次删除的关联会残留在正式表里
        // （c_index 变了但 id 没变的情况更隐蔽——顺序会静默错掉）
        courseCataSubjectMapper.deleteByCourseId(courseId);
        if (CollectionUtils.isEmpty(drafts)) {
            // 题目不是必填项：课程可以一道题都不挂
            log.info("节点-题目关联上架：courseId={}, 无关联数据", courseId);
            return;
        }
        int n = courseCataSubjectMapper.insertFromDraft(courseId);
        if (n != drafts.size()) {
            throw new BizIllegalException(CourseErrorInfo.Msg.COURSE_UP_SHELF_INCOMPLETE);
        }
        // 删除草稿（数据现在只在正式表里）
        baseMapper.deleteByCourseId(courseId);
        log.info("节点-题目关联上架：courseId={}, 共 {} 条", courseId, n);
    }

    @Override
    public void copyFromShelf(Long courseId) {
        int n = baseMapper.insertFromShelf(courseId);
        log.info("节点-题目关联回到草稿：courseId={}, 共 {} 条", courseId, n);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByCourseId(Long courseId) {
        baseMapper.deleteByCourseId(courseId);
        courseCataSubjectMapper.deleteByCourseId(courseId);
    }
}
