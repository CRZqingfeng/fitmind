package com.fitmind.search.service.impl;

import com.fitmind.api.client.course.CourseClient;
import com.fitmind.api.dto.course.CourseSearchInfoDTO;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.search.constants.SearchErrorInfo;
import com.fitmind.search.domain.po.CourseDoc;
import com.fitmind.search.repository.CourseRepository;
import com.fitmind.search.service.ICourseIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 课程索引同步实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseIndexServiceImpl implements ICourseIndexService {

    private final CourseClient courseClient;
    private final CourseRepository courseRepository;

    @Override
    public void saveCourseIndex(Long courseId) {
        CourseSearchInfoDTO info = courseClient.querySearchInfo(courseId);
        if (info == null) {
            // 两种可能：课程真被删了，或 fm-course 不可用（Feign 降级返回 null）。
            // 区分不了，所以只记日志——宁可少同步一条索引，也不要把索引里的已有数据删掉。
            log.warn("课程检索信息为空，跳过索引同步。courseId={}", courseId);
            throw new BizIllegalException(SearchErrorInfo.Msg.COURSE_DATA_MISSING);
        }
        courseRepository.save(toDoc(info));
        log.info("课程索引已同步：courseId={}, name={}", courseId, info.getName());
    }

    @Override
    public void deleteCourseIndex(Long courseId) {
        courseRepository.deleteById(courseId);
        log.info("课程索引已删除：courseId={}", courseId);
    }

    /**
     * 课程检索信息 → ES 文档。
     */
    private CourseDoc toDoc(CourseSearchInfoDTO info) {
        List<Long> teacherIds = info.getTeachers() == null
                ? List.of()
                : info.getTeachers().stream()
                .map(CourseSearchInfoDTO.TeacherInfo::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
        List<String> teacherNames = info.getTeachers() == null
                ? List.of()
                : info.getTeachers().stream()
                .map(CourseSearchInfoDTO.TeacherInfo::getName)
                .filter(java.util.Objects::nonNull)
                .toList();
        List<String> actionNames = info.getActionNames() == null
                ? List.of()
                : info.getActionNames().stream().filter(java.util.Objects::nonNull).toList();

        return CourseDoc.builder()
                .id(info.getId())
                .name(info.getName())
                .categoryIdLv1(info.getFirstCateId())
                .categoryIdLv2(info.getSecondCateId())
                .categoryIdLv3(info.getThirdCateId())
                .categoryPath(info.getCategoryPath())
                // 库里 free 是 1/2，索引里用 boolean 更好查询
                .free(Integer.valueOf(1).equals(info.getFree()))
                .type(info.getCourseType())
                .price(info.getPrice())
                .score(info.getScore())
                // 报名人数：M4 交易模块上线后才有真实值，这里先置 0 占位
                .sold(0)
                .teacherIds(teacherIds)
                .teacherNames(teacherNames)
                .sections(info.getSectionNum())
                .coverUrl(info.getCoverUrl())
                .publishTime(info.getPublishTime())
                .updateTime(info.getUpdateTime())
                .goalType(info.getGoalType())
                .trainingLevel(info.getTrainingLevel())
                .equipment(info.getEquipment())
                .totalDays(info.getTotalDays())
                .mediaDuration(info.getMediaDuration())
                .trainingDays(info.getTrainingDays())
                .actionNames(actionNames)
                .build();
    }
}
