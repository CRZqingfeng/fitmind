package com.fitmind.course.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.api.client.user.UserClient;
import com.fitmind.api.dto.course.CourseCatalogueDTO;
import com.fitmind.api.dto.course.CourseFullDTO;
import com.fitmind.api.dto.course.CataSimpleDTO;
import com.fitmind.api.dto.course.CourseSimpleDTO;
import com.fitmind.api.dto.course.CourseSearchInfoDTO;
import com.fitmind.api.dto.user.CoachDTO;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.course.constants.CourseStatus;
import com.fitmind.course.domain.po.Category;
import com.fitmind.course.domain.po.Course;
import com.fitmind.course.domain.po.CourseCatalogue;
import com.fitmind.course.domain.po.CourseTeacher;
import com.fitmind.course.domain.query.CoursePageQuery;
import com.fitmind.course.domain.vo.CoursePageVO;
import com.fitmind.course.mapper.CategoryMapper;
import com.fitmind.course.mapper.CourseCatalogueMapper;
import com.fitmind.course.mapper.CourseMapper;
import com.fitmind.course.mapper.CourseTeacherMapper;
import com.fitmind.course.service.ICourseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 课程（正式表）服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseServiceImpl extends ServiceImpl<CourseMapper, Course> implements ICourseService {

    /** 目录类型：训练日（章） */
    private static final int CATA_TYPE_DAY = 1;

    /** 目录类型：训练动作（节） */
    private static final int CATA_TYPE_ACTION = 2;

    private final CategoryMapper categoryMapper;
    private final CourseCatalogueMapper courseCatalogueMapper;
    private final CourseTeacherMapper courseTeacherMapper;
    private final UserClient userClient;

    @Override
    public PageDTO<CoursePageVO> queryForPage(CoursePageQuery query) {
        LambdaQueryWrapper<Course> wrapper = new LambdaQueryWrapper<Course>()
                .like(isNotBlank(query.getName()), Course::getName, query.getName())
                .eq(query.getCourseType() != null, Course::getCourseType, query.getCourseType())
                .eq(query.getStatus() != null, Course::getStatus, query.getStatus())
                .eq(query.getFirstCateId() != null, Course::getFirstCateId, query.getFirstCateId());
        var page = page(query.toMpPageDefaultSortByCreateTimeDesc(), wrapper);
        return PageDTO.of(page, this::toVO);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        updateById(new Course().setId(id).setStatus(status));
    }

    @Override
    public CourseSearchInfoDTO querySearchInfo(Long id) {
        Course course = getById(id);
        if (course == null) {
            // 课程不存在（比如刚被删掉），返回 null 让 fm-search 走"删除索引"分支
            log.warn("查询课程检索信息失败：课程不存在。id={}", id);
            return null;
        }

        CourseSearchInfoDTO dto = new CourseSearchInfoDTO();
        BeanUtil.copyProperties(course, dto);

        fillCategories(dto, course);
        fillTeachers(dto, id);
        fillCatalogues(dto, id);
        return dto;
    }

    /**
     * 填充三级分类名与分类路径。
     */
    private void fillCategories(CourseSearchInfoDTO dto, Course course) {
        List<Long> cateIds = new ArrayList<>();
        for (Long cid : new Long[]{course.getFirstCateId(), course.getSecondCateId(), course.getThirdCateId()}) {
            if (cid != null && !cateIds.contains(cid)) {
                cateIds.add(cid);
            }
        }
        if (cateIds.isEmpty()) {
            return;
        }
        Map<Long, String> nameMap = categoryMapper.selectBatchIds(cateIds).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName, (a, b) -> a));

        dto.setFirstCateName(nameMap.get(course.getFirstCateId()));
        dto.setSecondCateName(nameMap.get(course.getSecondCateId()));
        dto.setThirdCateName(nameMap.get(course.getThirdCateId()));

        // 分类路径「增肌 > 胸部 > 哑铃」：检索结果直接展示，也可整体作为一个可检索字段
        String path = java.util.stream.Stream.of(
                        dto.getFirstCateName(), dto.getSecondCateName(), dto.getThirdCateName())
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" > "));
        dto.setCategoryPath(path.isBlank() ? null : path);
    }

    /**
     * 填充教练姓名/头像（需跨服务调 fm-user）。
     */
    private void fillTeachers(CourseSearchInfoDTO dto, Long courseId) {
        List<CourseTeacher> relations = courseTeacherMapper.selectList(
                new LambdaQueryWrapper<CourseTeacher>()
                        .eq(CourseTeacher::getCourseId, courseId)
                        .orderByAsc(CourseTeacher::getCIndex));
        if (relations.isEmpty()) {
            dto.setTeachers(List.of());
            return;
        }

        List<Long> teacherIds = relations.stream().map(CourseTeacher::getTeacherId).distinct().toList();
        // fm-user 不可用时降级返回 null，此时至少把 id 带上，不至于让整个检索同步失败
        Map<Long, CoachDTO> coachMap = new HashMap<>();
        try {
            List<CoachDTO> coaches = userClient.queryCoaches(teacherIds);
            if (coaches != null) {
                coachMap = coaches.stream()
                        .filter(c -> c.getId() != null)
                        .collect(Collectors.toMap(CoachDTO::getId, c -> c, (a, b) -> a));
            }
        } catch (Exception e) {
            log.warn("查询教练信息失败，检索信息将只保留教练id。courseId={}，原因：{}", courseId, e.getMessage());
        }

        List<CourseSearchInfoDTO.TeacherInfo> teachers = new ArrayList<>();
        for (Long tid : teacherIds) {
            CourseSearchInfoDTO.TeacherInfo info = new CourseSearchInfoDTO.TeacherInfo();
            info.setId(tid);
            CoachDTO coach = coachMap.get(tid);
            if (coach != null) {
                info.setName(coach.getName());
                info.setIcon(coach.getIcon());
            }
            teachers.add(info);
        }
        dto.setTeachers(teachers);
    }

    /**
     * 填充训练日 / 训练动作。
     *
     * <p>ES 文档里这几项是可检索内容的主体——用户很可能直接搜「哑铃卧推」，
     * 而课名里未必带这个词。</p>
     */
    private void fillCatalogues(CourseSearchInfoDTO dto, Long courseId) {
        List<CourseCatalogue> catas = courseCatalogueMapper.selectList(
                new LambdaQueryWrapper<CourseCatalogue>()
                        .eq(CourseCatalogue::getCourseId, courseId)
                        .orderByAsc(CourseCatalogue::getCIndex));
        if (catas.isEmpty()) {
            dto.setTrainingDays(List.of());
            dto.setActionNames(List.of());
            dto.setActions(List.of());
            return;
        }

        // 训练日 id -> 名称，供动作回填所属训练日
        Map<Long, String> dayNameMap = new LinkedHashMap<>();
        for (CourseCatalogue c : catas) {
            if (isType(c, CATA_TYPE_DAY)) {
                dayNameMap.put(c.getId(), c.getName());
            }
        }

        List<String> actionNames = new ArrayList<>();
        List<CourseSearchInfoDTO.ActionInfo> actions = new ArrayList<>();
        for (CourseCatalogue c : catas) {
            if (!isType(c, CATA_TYPE_ACTION)) {
                continue;
            }
            actionNames.add(c.getName());

            CourseSearchInfoDTO.ActionInfo a = new CourseSearchInfoDTO.ActionInfo();
            a.setName(c.getName());
            a.setDayName(dayNameMap.get(c.getParentCatalogueId()));
            a.setMediaDuration(c.getMediaDuration());
            a.setSets(c.getSets());
            a.setReps(c.getReps());
            a.setRestSeconds(c.getRestSeconds());
            a.setWeight(c.getWeight());
            a.setActionType(c.getActionType());
            a.setTargetMuscles(c.getTargetMuscles());
            actions.add(a);
        }

        dto.setTrainingDays(new ArrayList<>(dayNameMap.values()));
        dto.setActionNames(actionNames);
        dto.setActions(actions);
    }

    @Override
    public CourseFullDTO queryCourseFull(Long id, boolean withCatalogues) {
        Course course = getById(id);
        if (course == null) {
            log.warn("查询课程全文失败：课程不存在。id={}", id);
            return null;
        }
        CourseFullDTO dto = BeanUtil.copyProperties(course, CourseFullDTO.class);
        if (withCatalogues) {
            List<CourseCatalogue> catas = courseCatalogueMapper.selectList(
                    new LambdaQueryWrapper<CourseCatalogue>()
                            .eq(CourseCatalogue::getCourseId, id)
                            .orderByAsc(CourseCatalogue::getCIndex));
            dto.setCatalogues(catas.stream().map(this::toCatalogueDTO).toList());
        }
        return dto;
    }

    @Override
    public List<CourseSimpleDTO> querySimpleList(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return listByIds(ids).stream().map(this::toSimpleDTO).toList();
    }

    @Override
    public List<CataSimpleDTO> queryCataSimpleList(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return courseCatalogueMapper.selectBatchIds(ids).stream()
                .map(c -> CataSimpleDTO.of(c.getId(), c.getName(), c.getType()))
                .toList();
    }

    private CourseSimpleDTO toSimpleDTO(Course c) {
        CourseSimpleDTO d = new CourseSimpleDTO();
        d.setId(c.getId());
        d.setName(c.getName());
        d.setCoverUrl(c.getCoverUrl());
        d.setCourseType(c.getCourseType());
        d.setFree(c.getFree());
        d.setPrice(c.getPrice());
        d.setStatus(c.getStatus());
        d.setSectionNum(c.getSectionNum());
        d.setValidDuration(c.getValidDuration());
        d.setGoalType(c.getGoalType());
        d.setTrainingLevel(c.getTrainingLevel());
        return d;
    }

    private CourseCatalogueDTO toCatalogueDTO(CourseCatalogue c) {
        CourseCatalogueDTO d = new CourseCatalogueDTO();
        d.setId(c.getId());
        d.setName(c.getName());
        d.setType(c.getType());
        d.setIndex(c.getCIndex());
        d.setParentCatalogueId(c.getParentCatalogueId());
        d.setMediaId(c.getMediaId());
        d.setMediaDuration(c.getMediaDuration());
        d.setTrailer(c.getTrailer() != null && c.getTrailer() == 1);
        d.setSets(c.getSets());
        d.setReps(c.getReps());
        d.setRestSeconds(c.getRestSeconds());
        d.setWeight(c.getWeight());
        return d;
    }

    private static boolean isType(CourseCatalogue c, int type) {
        return c.getType() != null && c.getType() == type;
    }

    private CoursePageVO toVO(Course c) {
        CoursePageVO vo = new CoursePageVO();
        vo.setId(c.getId());
        vo.setName(c.getName());
        vo.setCourseType(c.getCourseType());
        vo.setCoverUrl(c.getCoverUrl());
        vo.setThirdCateId(c.getThirdCateId());
        vo.setFree(c.getFree());
        vo.setPrice(c.getPrice());
        vo.setStatus(c.getStatus());
        vo.setStatusDesc(CourseStatus.desc(c.getStatus()));
        vo.setStep(c.getStep());
        vo.setSectionNum(c.getSectionNum());
        vo.setMediaDuration(c.getMediaDuration());
        vo.setPublishTimes(c.getPublishTimes());
        vo.setPublishTime(c.getPublishTime());
        vo.setCreateTime(c.getCreateTime());
        vo.setUpdateTime(c.getUpdateTime());
        return vo;
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
