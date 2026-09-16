package com.fitmind.learning.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.domain.query.PageQuery;
import com.fitmind.learning.domain.po.LearningLesson;
import com.fitmind.learning.domain.vo.LearningLessonVO;
import com.fitmind.learning.domain.vo.LearningPlanPageVO;

/**
 * 学习课表服务。
 */
public interface ILearningLessonService extends IService<LearningLesson> {

    /** 报名（免费课；重复报名幂等；付费课拒绝） */
    void enroll(Long courseId);

    /** 我的课程分页（latest_learn_time 倒序，可按状态筛选） */
    PageDTO<LearningLessonVO> queryMyLessons(Integer status, PageQuery query);

    /** 最近在学的一门课（首页「继续训练」） */
    LearningLessonVO queryMyCurrentLesson();

    /** 查询某课的学习状态（课程详情页判断已报名/已过期） */
    LearningLessonVO queryLessonByCourseId(Long courseId);

    /** 从课表移除课程（连同学习记录一起删，重新报名从零开始） */
    void removeCourse(Long courseId);

    /** 创建/修改学习计划（每周训练频率，1~7） */
    void createPlan(Long courseId, Integer freq);

    /** 我的计划统计（本周已练 / 计划总数）+ 分课计划 */
    LearningPlanPageVO queryMyPlans(PageQuery query);

    /** 查询某用户某课程的课表记录 */
    LearningLesson queryByUserAndCourseId(Long userId, Long courseId);

    /**
     * ★ 支付成功后写课表（<b>内部方法</b>，供 MQ 消费者调用）。
     *
     * <p>与 {@link #enroll(Long)} 的区别：</p>
     * <ul>
     *   <li>{@code enroll} 是<b>用户主动报名</b>，只接受免费课，且用户来自 {@code UserContext}</li>
     *   <li>本方法是<b>订单支付成功的后果</b>，接受付费课，用户由消息指定
     *       （MQ 消费线程里没有登录上下文）</li>
     * </ul>
     *
     * <p>两者最终落到同一张表、同一套幂等约束 —— 免费报名和付费购买的结果必须一致。</p>
     *
     * @return 本次真正新建的课表数量（重复的不算）
     */
    int addUserLessons(Long userId, List<Long> courseIds);

    /** 过期扫描：把 expire_time 已过的课表置为 EXPIRED。返回处理条数 */
    int expireScan();

    /**
     * ★ 退款成功后撤销报名（<b>内部方法</b>，供 MQ 消费者调用）。
     *
     * <p>与 {@link #addUserLessons} 正好互为逆操作，两者也必须遵守同一套约束：</p>
     * <ul>
     *   <li>用户由消息指定（MQ 消费线程里没有登录上下文）</li>
     *   <li>幂等：删一条不存在的课表返回 0 行，重复投递无副作用</li>
     * </ul>
     *
     * <p><b>为什么要删干净（连学习记录一起）</b>：钱都退了，课程权益不该留 ——
     * 包括学习进度。用户重新购买时会走一遍 {@code addUserLessons}，
     * 从零开始（这也符合"重新买 = 重新开始"的语义）。</p>
     *
     * <p>不回收训练获得的积分：积分是"已经付出过的行为"的记录，
     * 退款退的是课程权益，不是抹掉用户练过这件事。</p>
     *
     * @return 本次真正删除的课表数量（本来就没有的不算）
     */
    int removeUserLessons(Long userId, List<Long> courseIds);
}
