package com.fitmind.learning.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.api.client.course.CourseClient;
import com.fitmind.api.dto.course.CourseSimpleDTO;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.domain.query.PageQuery;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.utils.UserContext;
import com.fitmind.learning.constants.LearningConstants;
import com.fitmind.learning.constants.LearningErrorInfo;
import com.fitmind.learning.domain.po.LearningLesson;
import com.fitmind.learning.domain.po.LearningRecord;
import com.fitmind.learning.domain.vo.LearningLessonVO;
import com.fitmind.learning.domain.vo.LearningPlanPageVO;
import com.fitmind.learning.domain.vo.LearningPlanVO;
import com.fitmind.learning.mapper.LearningLessonMapper;
import com.fitmind.learning.mapper.LearningRecordMapper;
import com.fitmind.learning.service.ILearningLessonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 学习课表实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningLessonServiceImpl extends ServiceImpl<LearningLessonMapper, LearningLesson>
        implements ILearningLessonService {

    private final CourseClient courseClient;
    private final LearningRecordMapper recordMapper;

    @Override
    public void enroll(Long courseId) {
        Long userId = UserContext.getUser();

        // 1.报名前先到课程服务校验（上架状态 / 免费 / 有效期）——数据归属方负责自证合法
        var full = courseClient.queryCourseFull(courseId, false);
        if (full == null) {
            throw new BizIllegalException(LearningErrorInfo.Msg.COURSE_NOT_EXISTS);
        }
        if (full.getStatus() == null || full.getStatus() != 2) {
            throw new BizIllegalException(LearningErrorInfo.Msg.COURSE_NOT_SHELF);
        }
        if (full.getFree() == null || full.getFree() != 1) {
            throw new BizIllegalException(LearningErrorInfo.Msg.COURSE_PAID);
        }

        // 2.重复报名直接幂等返回（有课表且未过期）
        LearningLesson exist = queryByUserAndCourseId(userId, courseId);
        if (exist != null && !Objects.equals(exist.getStatus(), LearningConstants.LESSON_EXPIRED)) {
            log.info("重复报名幂等返回：userId={}, courseId={}", userId, courseId);
            return;
        }
        if (exist != null) {
            // 过期后重新报名：重置状态与到期时间，从头学
            exist.setStatus(LearningConstants.LESSON_NOT_BEGIN)
                    .setLearnedSections(0)
                    .setLatestSectionId(null)
                    .setLatestLearnTime(null)
                    .setExpireTime(calcExpireTime(full.getValidDuration()));
            updateById(exist);
            return;
        }

        // 3.写入课表；并发下唯一索引兜底
        LearningLesson lesson = new LearningLesson()
                .setUserId(userId)
                .setCourseId(courseId)
                .setStatus(LearningConstants.LESSON_NOT_BEGIN)
                .setPlanStatus(LearningConstants.PLAN_NO)
                .setLearnedSections(0)
                .setExpireTime(calcExpireTime(full.getValidDuration()));
        try {
            save(lesson);
        } catch (DuplicateKeyException e) {
            log.info("并发重复报名被唯一索引拦截：userId={}, courseId={}", userId, courseId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int addUserLessons(Long userId, List<Long> courseIds) {
        if (userId == null || courseIds == null || courseIds.isEmpty()) {
            return 0;
        }
        int created = 0;
        for (Long courseId : courseIds) {
            // 1. 幂等：已有未过期的课表就跳过
            LearningLesson exist = queryByUserAndCourseId(userId, courseId);
            if (exist != null && !Objects.equals(exist.getStatus(), LearningConstants.LESSON_EXPIRED)) {
                log.info("课表已存在，跳过：userId={}, courseId={}", userId, courseId);
                continue;
            }
            // 2. 有效期仍要问课程服务（数据源只有一份）
            var full = courseClient.queryCourseFull(courseId, false);
            if (full == null) {
                log.error("课程不存在，跳过写课表：courseId={}", courseId);
                continue;
            }
            LocalDateTime expireTime = calcExpireTime(full.getValidDuration());

            if (exist != null) {
                // 过期后重新购买：重置进度，从头学
                exist.setStatus(LearningConstants.LESSON_NOT_BEGIN)
                        .setLearnedSections(0)
                        .setLatestSectionId(null)
                        .setLatestLearnTime(null)
                        .setExpireTime(expireTime);
                updateById(exist);
                created++;
                continue;
            }
            try {
                save(new LearningLesson()
                        .setUserId(userId)
                        .setCourseId(courseId)
                        .setStatus(LearningConstants.LESSON_NOT_BEGIN)
                        .setPlanStatus(LearningConstants.PLAN_NO)
                        .setLearnedSections(0)
                        .setExpireTime(expireTime));
                created++;
            } catch (DuplicateKeyException e) {
                // ★ 幂等的最后一道闸：并发下唯一索引冲突，说明已经写过了
                log.info("并发重复写课表被唯一索引拦截：userId={}, courseId={}", userId, courseId);
            }
        }
        log.info("支付成功写入课表：userId={}, 课程 {} 门，新建 {} 条", userId, courseIds.size(), created);
        return created;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int removeUserLessons(Long userId, List<Long> courseIds) {
        if (userId == null || courseIds == null || courseIds.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (Long courseId : courseIds) {
            LearningLesson lesson = queryByUserAndCourseId(userId, courseId);
            if (lesson == null) {
                // ★ 幂等的关键：本来就没有 → 什么都不做。
                //   MQ 是 at-least-once，重复投递时走到这里就自然停住，
                //   不需要额外的"消息去过重"表
                log.info("课表不存在，无需撤销（幂等）：userId={}, courseId={}", userId, courseId);
                continue;
            }
            removeById(lesson.getId());
            // 学习记录随课表一起删 —— 与 removeCourse 的处理一致：
            // 重新购买时 lesson_id 是新的行，旧记录没有归属，留着就是孤儿
            recordMapper.delete(Wrappers.<LearningRecord>lambdaQuery()
                    .eq(LearningRecord::getLessonId, lesson.getId()));
            removed++;
        }
        log.info("退款成功撤销课表：userId={}, 课程 {} 门，实际删除 {} 条", userId, courseIds.size(), removed);
        return removed;
    }

    private LocalDateTime calcExpireTime(Integer validDuration) {
        if (validDuration == null || validDuration <= 0) {
            return null; // 永不过期
        }
        return LocalDateTime.now().plusDays(validDuration);
    }

    @Override
    public PageDTO<LearningLessonVO> queryMyLessons(Integer status, PageQuery query) {
        Long userId = UserContext.getUser();
        var page = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(status != null, LearningLesson::getStatus, status)
                .page(query.toMpPage("latest_learn_time", false));
        if (page.getRecords().isEmpty()) {
            return PageDTO.empty(page);
        }
        return new PageDTO<>(page.getTotal(), page.getPages(), toVOs(page.getRecords()));
    }

    @Override
    public LearningLessonVO queryMyCurrentLesson() {
        Long userId = UserContext.getUser();
        LearningLesson lesson = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .isNotNull(LearningLesson::getLatestLearnTime)
                .orderByDesc(LearningLesson::getLatestLearnTime)
                .last("limit 1")
                .one();
        if (lesson == null) {
            return null;
        }
        return toVOs(List.of(lesson)).get(0);
    }

    @Override
    public LearningLessonVO queryLessonByCourseId(Long courseId) {
        Long userId = UserContext.getUser();
        LearningLesson lesson = queryByUserAndCourseId(userId, courseId);
        if (lesson == null) {
            return null;
        }
        return toVOs(List.of(lesson)).get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeCourse(Long courseId) {
        Long userId = UserContext.getUser();
        LearningLesson lesson = queryByUserAndCourseId(userId, courseId);
        if (lesson == null) {
            throw new BizIllegalException(LearningErrorInfo.Msg.LESSON_NOT_EXISTS);
        }
        removeById(lesson.getId());
        // 学习记录随课表一起删：重新报名时从零开始（lesson_id 是新行，旧记录没有归属）
        recordMapper.delete(Wrappers.<LearningRecord>lambdaQuery()
                .eq(LearningRecord::getLessonId, lesson.getId()));
        log.info("课程已从课表移除：userId={}, courseId={}", userId, courseId);
    }

    @Override
    public void createPlan(Long courseId, Integer freq) {
        Long userId = UserContext.getUser();
        LearningLesson lesson = queryByUserAndCourseId(userId, courseId);
        if (lesson == null) {
            throw new BizIllegalException(LearningErrorInfo.Msg.LESSON_NOT_EXISTS);
        }
        if (freq == null || freq < LearningConstants.WEEK_FREQ_MIN
                || freq > LearningConstants.WEEK_FREQ_MAX) {
            throw new BizIllegalException(LearningErrorInfo.Msg.WEEK_FREQ_ILLEGAL);
        }
        LearningLesson update = new LearningLesson()
                .setId(lesson.getId())
                .setWeekFreq(freq);
        if (!Objects.equals(lesson.getPlanStatus(), LearningConstants.PLAN_RUNNING)) {
            update.setPlanStatus(LearningConstants.PLAN_RUNNING);
        }
        updateById(update);
        log.info("学习计划已保存：userId={}, courseId={}, weekFreq={}", userId, courseId, freq);
    }

    @Override
    public LearningPlanPageVO queryMyPlans(PageQuery query) {
        Long userId = UserContext.getUser();

        // 1.本周统计：已完成数来自 learning_record 的 finish_time
        LocalDate now = LocalDate.now();
        LocalDateTime weekBegin = now.with(DayOfWeek.MONDAY).atStartOfDay();
        LocalDateTime weekEnd = weekBegin.plusWeeks(1);
        Long weekFinished = recordMapper.selectCount(new LambdaQueryWrapper<LearningRecord>()
                .eq(LearningRecord::getUserId, userId)
                .eq(LearningRecord::getFinished, 1)
                .ge(LearningRecord::getFinishTime, weekBegin)
                .lt(LearningRecord::getFinishTime, weekEnd));

        // 2.分课计划（进行中的计划）
        var page = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getPlanStatus, LearningConstants.PLAN_RUNNING)
                .page(query.toMpPage("update_time", false));

        LearningPlanPageVO vo = new LearningPlanPageVO();
        vo.setWeekFinished(weekFinished == null ? 0 : weekFinished.intValue());
        vo.setWeekTotalPlan(page.getRecords().stream()
                .mapToInt(l -> l.getWeekFreq() == null ? 0 : l.getWeekFreq()).sum());
        vo.setList(page.getRecords().stream().map(l -> {
            LearningPlanVO plan = BeanUtil.copyProperties(l, LearningPlanVO.class);
            plan.setStatusDesc(lessonStatusDesc(l.getStatus()));
            return plan;
        }).toList());
        // 课程名/封面批量补齐（避免 N+1 单查）
        List<Long> planCourseIds = page.getRecords().stream()
                .map(LearningLesson::getCourseId).distinct().toList();
        List<CourseSimpleDTO> courses = courseClient.queryCourseSimpleList(planCourseIds);
        Map<Long, CourseSimpleDTO> courseMap = courses == null ? Map.of()
                : courses.stream().collect(Collectors.toMap(CourseSimpleDTO::getId, Function.identity(), (a, b) -> a));
        for (LearningPlanVO plan : vo.getList()) {
            CourseSimpleDTO course = courseMap.get(plan.getCourseId());
            if (course != null) {
                plan.setCourseName(course.getName());
                plan.setCoverUrl(course.getCoverUrl());
                plan.setSections(course.getSectionNum());
            }
        }
        return vo;
    }

    @Override
    public LearningLesson queryByUserAndCourseId(Long userId, Long courseId) {
        return lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
    }

    @Override
    public int expireScan() {
        int n = baseMapper.update(null, Wrappers.<LearningLesson>lambdaUpdate()
                .set(LearningLesson::getStatus, LearningConstants.LESSON_EXPIRED)
                .in(LearningLesson::getStatus, LearningConstants.LESSON_NOT_BEGIN,
                        LearningConstants.LESSON_LEARNING, LearningConstants.LESSON_FINISHED)
                .isNotNull(LearningLesson::getExpireTime)
                .lt(LearningLesson::getExpireTime, LocalDateTime.now()));
        if (n > 0) {
            log.info("课表过期扫描完成：处理 {} 条", n);
        }
        return n;
    }

    // ==================== 内部方法 ====================

    private List<LearningLessonVO> toVOs(List<LearningLesson> lessons) {
        List<Long> courseIds = lessons.stream().map(LearningLesson::getCourseId).distinct().toList();
        List<CourseSimpleDTO> courses = courseClient.queryCourseSimpleList(courseIds);
        Map<Long, CourseSimpleDTO> courseMap = courses == null ? Map.of()
                : courses.stream().collect(Collectors.toMap(CourseSimpleDTO::getId, Function.identity(), (a, b) -> a));
        List<LearningLessonVO> vos = new ArrayList<>(lessons.size());
        for (LearningLesson lesson : lessons) {
            LearningLessonVO vo = BeanUtil.copyProperties(lesson, LearningLessonVO.class);
            CourseSimpleDTO course = courseMap.get(lesson.getCourseId());
            if (course != null) {
                vo.setCourseName(course.getName());
                vo.setCoverUrl(course.getCoverUrl());
                vo.setSections(course.getSectionNum());
            }
            vo.setStatusDesc(lessonStatusDesc(lesson.getStatus()));
            vos.add(vo);
        }
        return vos;
    }

    private static String lessonStatusDesc(Integer status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case LearningConstants.LESSON_NOT_BEGIN -> "未学习";
            case LearningConstants.LESSON_LEARNING -> "学习中";
            case LearningConstants.LESSON_FINISHED -> "已学完";
            case LearningConstants.LESSON_EXPIRED -> "已过期";
            default -> "未知";
        };
    }
}
