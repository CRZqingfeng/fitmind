package com.fitmind.learning.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.api.client.course.CourseClient;
import com.fitmind.api.dto.course.CourseFullDTO;
import com.fitmind.common.constants.MqConstants;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.utils.RabbitMqHelper;
import com.fitmind.common.utils.UserContext;
import com.fitmind.learning.constants.LearningConstants;
import com.fitmind.learning.constants.LearningErrorInfo;
import com.fitmind.learning.domain.po.LearningLesson;
import com.fitmind.learning.domain.po.LearningRecord;
import com.fitmind.learning.domain.vo.LearningProgressVO;
import com.fitmind.learning.domain.vo.LearningRecordVO;
import com.fitmind.learning.handler.LearningRecordDelayTaskHandler;
import com.fitmind.learning.mapper.LearningRecordMapper;
import com.fitmind.common.mq.message.SectionLearnedMessage;
import com.fitmind.learning.service.ILearningLessonService;
import com.fitmind.learning.service.ILearningRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 学习记录实现（动作级进度）。
 *
 * <p>核心分支：上报的进度<b>过半</b>（moment*2 &gt;= duration）→ 直接完成并推进课表；
 * <b>未过半</b> → 写缓存 + ZSet 延迟任务，由
 * {@link LearningRecordDelayTaskHandler} 在停播后合并落库。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningRecordServiceImpl extends ServiceImpl<LearningRecordMapper, LearningRecord>
        implements ILearningRecordService {

    private final ILearningLessonService lessonService;
    private final LearningRecordDelayTaskHandler delayHandler;
    private final CourseClient courseClient;
    private final RabbitMqHelper rabbitMqHelper;

    @Override
    public LearningProgressVO queryProgress(Long courseId) {
        Long userId = UserContext.getUser();
        LearningLesson lesson = lessonService.queryByUserAndCourseId(userId, courseId);
        if (lesson == null) {
            throw new BizIllegalException(LearningErrorInfo.Msg.LESSON_NOT_EXISTS);
        }
        List<LearningRecordVO> records = lambdaQuery()
                .eq(LearningRecord::getLessonId, lesson.getId())
                .list().stream().map(r -> {
                    LearningRecordVO vo = new LearningRecordVO();
                    vo.setSectionId(r.getSectionId());
                    vo.setMoment(r.getMoment());
                    vo.setFinished(r.getFinished() != null && r.getFinished() == 1);
                    vo.setFinishTime(r.getFinishTime());
                    return vo;
                }).toList();

        LearningProgressVO vo = new LearningProgressVO();
        vo.setLessonId(lesson.getId());
        vo.setCourseId(courseId);
        vo.setStatus(lesson.getStatus());
        vo.setStatusDesc(lessonStatusDesc(lesson.getStatus()));
        vo.setLatestSectionId(lesson.getLatestSectionId());
        vo.setRecords(records);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addRecord(Long lessonId, Long sectionId, Integer moment, Integer duration) {
        Long userId = UserContext.getUser();

        // 1.课表必须存在且未过期
        LearningLesson lesson = lessonService.getById(lessonId);
        if (lesson == null || !Objects.equals(lesson.getUserId(), userId)) {
            throw new BizIllegalException(LearningErrorInfo.Msg.LESSON_NOT_EXISTS);
        }
        if (Objects.equals(lesson.getStatus(), LearningConstants.LESSON_EXPIRED)) {
            throw new BizIllegalException(LearningErrorInfo.Msg.LESSON_EXPIRED);
        }

        // 2.查旧记录：先缓存后库（缓存是最新值，可能还没合并落库）
        LearningRecord old = delayHandler.readCache(lessonId, sectionId);
        if (old == null) {
            old = lambdaQuery()
                    .eq(LearningRecord::getLessonId, lessonId)
                    .eq(LearningRecord::getSectionId, sectionId)
                    .one();
        }

        // 3.判定是否"看完"：moment * 2 >= duration（播放过半）
        boolean finished = duration != null && duration > 0
                && moment * LearningConstants.FINISH_RATIO_DENOMINATOR >= duration;

        if (old == null) {
            // 3.1.首次上报：直接插入
            LearningRecord record = new LearningRecord()
                    .setLessonId(lessonId)
                    .setSectionId(sectionId)
                    .setUserId(userId)
                    .setMoment(moment)
                    .setFinished(finished ? 1 : 0);
            if (finished) {
                record.setFinishTime(LocalDateTime.now());
            }
            save(record);
            if (!finished) {
                log.info("进度上报[首次·未过半→直接落库+写缓存]：lessonId={}, sectionId={}, moment={}",
                        lessonId, sectionId, moment);
                delayHandler.writeCache(record);
                return;
            }
            log.info("进度上报[首次·过半→直接落库+推进课表]：lessonId={}, sectionId={}, moment={}",
                    lessonId, sectionId, moment);
            handleLessonChanges(lesson, sectionId);
            return;
        }

        if (old.getFinished() != null && old.getFinished() == 1) {
            // 3.2.已完成：只更新断点（不重复推进课表、不重复发 MQ）
            log.info("进度上报[已完成→只更新断点]：lessonId={}, sectionId={}, moment={}",
                    lessonId, sectionId, moment);
            delayHandler.writeCache(new LearningRecord()
                    .setId(old.getId())
                    .setLessonId(lessonId)
                    .setSectionId(sectionId)
                    .setUserId(userId)
                    .setMoment(moment)
                    .setFinished(1));
            delayHandler.addDelayTask(new LearningRecord()
                    .setId(old.getId())
                    .setLessonId(lessonId)
                    .setSectionId(sectionId));
            return;
        }

        if (!finished) {
            // 3.3.未过半：写缓存 + 延迟任务（合并落库），不推进课表
            log.info("进度上报[非首次·未过半→缓存+延迟任务]：lessonId={}, sectionId={}, moment={}",
                    lessonId, sectionId, moment);
            LearningRecord merged = new LearningRecord()
                    .setId(old.getId())
                    .setLessonId(lessonId)
                    .setSectionId(sectionId)
                    .setUserId(userId)
                    .setMoment(moment)
                    .setFinished(0);
            delayHandler.writeCache(merged);
            delayHandler.addDelayTask(merged);
            return;
        }

        // 3.4.首次完成：直接写终态 → 推进课表 → 发积分事件
        log.info("进度上报[非首次·过半→写终态+推进课表]：lessonId={}, sectionId={}, moment={}",
                lessonId, sectionId, moment);
        boolean ok = lambdaUpdate()
                .set(LearningRecord::getMoment, moment)
                .set(LearningRecord::getFinished, 1)
                .set(LearningRecord::getFinishTime, LocalDateTime.now())
                .eq(LearningRecord::getId, old.getId())
                .update();
        if (!ok) {
            throw new BizIllegalException(LearningErrorInfo.Msg.RECORD_SAVE_FAILED);
        }
        delayHandler.cleanCache(lessonId, sectionId);
        handleLessonChanges(lesson, sectionId);
    }

    /**
     * 课表推进：原子累加 learned_sections + 更新断点 + 判断全部完成。
     *
     * <p>为什么用 setSql 而不是 set：两个请求同时完成两个动作时，
     * set 会让后写的覆盖先写的（丢失更新），setSql 是数据库端原子累加。</p>
     */
    private void handleLessonChanges(LearningLesson lesson, Long sectionId) {
        // 1.查课程总动作数（判断是否全部完成）
        CourseFullDTO course = courseClient.queryCourseFull(lesson.getCourseId(), false);
        if (course == null) {
            throw new BizIllegalException(LearningErrorInfo.Msg.COURSE_NOT_EXISTS);
        }

        // 2.原子累加并更新断点
        boolean allLearned = course.getSectionNum() != null
                && lesson.getLearnedSections() + 1 >= course.getSectionNum();
        lessonService.lambdaUpdate()
                .set(Objects.equals(lesson.getStatus(), LearningConstants.LESSON_NOT_BEGIN),
                        LearningLesson::getStatus, LearningConstants.LESSON_LEARNING)
                .set(allLearned, LearningLesson::getStatus, LearningConstants.LESSON_FINISHED)
                .set(LearningLesson::getLatestSectionId, sectionId)
                .set(LearningLesson::getLatestLearnTime, LocalDateTime.now())
                .setSql("learned_sections = learned_sections + 1")
                .eq(LearningLesson::getId, lesson.getId())
                .update();

        // 3.完成训练动作 → 发积分事件（M3-2 的积分模块消费）。
        //   ⚠️ 两点注意：
        //   a) 必须等事务提交后再发：消息先到消费者、数据还没提交，积分侧会查不到上下文
        //      （M2-3 的 sendAfterCommit 就是为此而生）
        //   b) 消息体必须带 lessonId/sectionId：积分侧要用它拼 bizId 做幂等，
        //      否则 MQ 重复投递会让用户重复得分（常见实现就没带，无法幂等）
        rabbitMqHelper.sendAfterCommit(MqConstants.Exchange.LEARNING_EXCHANGE,
                MqConstants.Key.SECTION_LEARNED,
                SectionLearnedMessage.of(lesson.getUserId(), lesson.getId(), sectionId));
        if (allLearned) {
            log.info("课程全部学完：lessonId={}, courseId={}, userId={}",
                    lesson.getId(), lesson.getCourseId(), lesson.getUserId());
        }
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
