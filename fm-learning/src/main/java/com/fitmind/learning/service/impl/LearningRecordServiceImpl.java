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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
        List<LearningRecord> rows = lambdaQuery()
                .eq(LearningRecord::getLessonId, lesson.getId())
                .list();

        // ★ 断点续播必须"先缓存后库"，与写路径（addRecord 的 ② 步）保持同一口径：
        //   未过半的上报只写了缓存、延迟 delay-seconds 才落库，
        //   此刻直接读库会拿到旧 moment —— 表现就是"进度条往回退"。
        //   用 MGET 批量读，避免 N 个动作 N 次 Redis 往返。
        Map<Long, LearningRecord> cacheMap = delayHandler.readCaches(lesson.getId(),
                rows.stream().map(LearningRecord::getSectionId).toList());

        List<LearningRecordVO> records = rows.stream().map(r -> {
            // 缓存里是尚未落库的最新值；未命中才回落到库里的值。
            // 缓存里的 finished 可信：分支 B（已完成再上报）写缓存时显式置了 finished=1。
            LearningRecord latest = cacheMap.getOrDefault(r.getSectionId(), r);
            LearningRecordVO vo = new LearningRecordVO();
            vo.setSectionId(r.getSectionId());
            vo.setMoment(latest.getMoment());
            vo.setFinished(latest.getFinished() != null && latest.getFinished() == 1);
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

    /**
     *
     * if (首次上报) {
     *         if (过半) → 直接落库 + 推进课表 + 发积分MQ
     *         if (未过半) → 直接落库 + 写缓存（未过半=还会继续播）
     *     }
     *     if (已完成) → 只更新断点（moment），不重复推进、不重复发MQ
     *     if (未过半) → 写缓存 + 登记延迟任务（合并）
     *     if (首次过半) → 写终态（finished=1, finishTime=now）+ 推进课表 + 清缓存
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addRecord(Long lessonId, Long sectionId, Integer moment, Integer duration) {
        Long userId = UserContext.getUser();

        // ① 校验：课表必须存在且属于当前用户、未过期
        LearningLesson lesson = lessonService.getById(lessonId);
        if (lesson == null || !Objects.equals(lesson.getUserId(), userId)) {
            throw new BizIllegalException(LearningErrorInfo.Msg.LESSON_NOT_EXISTS);
        }
        if (Objects.equals(lesson.getStatus(), LearningConstants.LESSON_EXPIRED)) {
            throw new BizIllegalException(LearningErrorInfo.Msg.LESSON_EXPIRED);
        }

        // ② 查旧记录：先缓存后库（缓存是最新值，可能还没落库）
        LearningRecord old = delayHandler.readCache(lessonId, sectionId);
        if (old == null) {
            old = lambdaQuery()
                    .eq(LearningRecord::getLessonId, lessonId)
                    .eq(LearningRecord::getSectionId, sectionId)
                    .one();
        }

        // ③ 判定是否看完：播放过半算完成  moment * 2 >= duration（播放过半）
        boolean finished = duration != null && duration > 0
                && moment * LearningConstants.FINISH_RATIO_DENOMINATOR >= duration;
        // ===== ④ 四大分支 =====
        if (old == null) {
            // 分支A：首次上报
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
            // A1：首次+未过半 → 落库 + 写缓存（后续上报走合并）
            if (!finished) {
                log.info("进度上报[首次·未过半→直接落库+写缓存]：lessonId={}, sectionId={}, moment={}",
                        lessonId, sectionId, moment);
                delayHandler.writeCache(record);
                return;
            }
            log.info("进度上报[首次·过半→直接落库+推进课表]：lessonId={}, sectionId={}, moment={}",
                    lessonId, sectionId, moment);
            // A2：首次+过半 → 直接推进课表 + 发积分MQ
            handleLessonChanges(lesson, sectionId);
            return;
        }

        if (old.getFinished() != null && old.getFinished() == 1) {
            // 分支B：已完成 → 只更新断点（不重复推进课表、不重复发MQ）
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
            // 分支C：未完成+未过半 → 写缓存 + 延迟任务（合并落库的核心路径）
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

        // 分支D：未完成+首次过半 → 写终态 + 清缓存 + 推进课表 + 发积分MQ
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
        // ★ 清缓存必须等事务提交后再做：Redis 不参与 DB 事务，
        //   若后面的 handleLessonChanges（远程调用 / 发 MQ）抛异常导致整体回滚，
        //   被删掉的"最新断点缓存"和被摘掉的延迟任务都撤不回来 —— 那条断点就白丢了。
        cleanCacheAfterCommit(lessonId, sectionId);
        handleLessonChanges(lesson, sectionId);
    }

    /**
     * 课表推进：原子累加 learned_sections + 更新断点 + 判断全部完成。
     *
     * <p>为什么用 setSql 而不是 set：两个请求同时完成两个动作时，
     * set 会让后写的覆盖先写的（丢失更新），setSql 是数据库端原子累加。</p>
     *
     * <p>为什么"是否全部学完"要重读一次：{@code setSql} 只保证了<b>计数</b>不丢，
     * 状态判定若沿用入参 {@code lesson} 的快照值，并发完成两个动作时会<b>漏判</b> ——
     * 详见方法内第 3 步的注释。</p>
     */
    private void handleLessonChanges(LearningLesson lesson, Long sectionId) {
        // 1.查课程总动作数（判断是否全部完成）
        CourseFullDTO course = courseClient.queryCourseFull(lesson.getCourseId(), false);
        if (course == null) {
            throw new BizIllegalException(LearningErrorInfo.Msg.COURSE_NOT_EXISTS);
        }

        // 2.原子累加并更新断点（状态这一步只处理"未开始 → 学习中"）
        lessonService.lambdaUpdate()
                .set(Objects.equals(lesson.getStatus(), LearningConstants.LESSON_NOT_BEGIN),
                        LearningLesson::getStatus, LearningConstants.LESSON_LEARNING)
                .set(LearningLesson::getLatestSectionId, sectionId)
                .set(LearningLesson::getLatestLearnTime, LocalDateTime.now())
                .setSql("learned_sections = learned_sections + 1")
                .eq(LearningLesson::getId, lesson.getId())
                .update();

        // 3.★ "是否全部学完"必须用「自增后的真实计数」判断，不能用方法开头 getById 的快照值：
        //   两个不同动作被并发完成时，两个事务读到的快照都是旧值 → 都算出"还没学完"
        //   → learned_sections 累加到顶、status 却停在"学习中"；
        //   而此后对已完成动作的上报走分支 B（已完成 → 不推进课表）→ 状态永远修不回来。
        //   同事务内 SELECT 可见自己刚 UPDATE 的结果，所以这里拿到的是自增后的真实计数。
        LearningLesson current = lessonService.getById(lesson.getId());
        boolean allLearned = current != null
                && course.getSectionNum() != null
                && current.getLearnedSections() != null
                && current.getLearnedSections() >= course.getSectionNum();
        if (allLearned) {
            lessonService.lambdaUpdate()
                    .set(LearningLesson::getStatus, LearningConstants.LESSON_FINISHED)
                    .eq(LearningLesson::getId, lesson.getId())
                    .update();
        }

        // 4.完成训练动作 → 发积分事件（M3-2 的积分模块消费）。
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

    /**
     * 清进度缓存与延迟任务 —— 但推迟到<b>事务提交之后</b>执行。
     *
     * <p>Redis 不参与 DB 事务：若在事务内就清掉，而后续步骤（远程调用 / 发 MQ）抛异常导致回滚，
     * 缓存里那条"最新断点"已经删了、ZSet 里的任务也摘了，两边都撤不回来。
     * 用 afterCommit 保证「整体成功才清」。</p>
     */
    private void cleanCacheAfterCommit(Long lessonId, Long sectionId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delayHandler.cleanCache(lessonId, sectionId);
                }
            });
        } else {
            // 无活动事务时（正常不会走到，addRecord 带 @Transactional）退化为直接清
            delayHandler.cleanCache(lessonId, sectionId);
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
