package com.fitmind.course.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.common.constants.MqConstants;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.utils.RabbitMqHelper;
import com.fitmind.common.utils.UserContext;
import com.fitmind.course.constants.CourseConstants;
import com.fitmind.course.constants.CourseErrorInfo;
import com.fitmind.course.constants.CourseStatus;
import com.fitmind.course.domain.dto.CourseBaseInfoSaveDTO;
import com.fitmind.course.domain.po.Course;
import com.fitmind.course.domain.po.CourseContent;
import com.fitmind.course.domain.po.CourseContentDraft;
import com.fitmind.course.domain.po.CourseDraft;
import com.fitmind.course.domain.query.CoursePageQuery;
import com.fitmind.course.domain.vo.CourseBaseInfoVO;
import com.fitmind.course.domain.vo.CoursePageVO;
import com.fitmind.course.domain.vo.CourseSaveVO;
import com.fitmind.course.domain.vo.NameExistVO;
import com.fitmind.course.mapper.CourseContentDraftMapper;
import com.fitmind.course.mapper.CourseContentMapper;
import com.fitmind.course.mapper.CourseDraftMapper;
import com.fitmind.course.mapper.CourseMapper;
import com.fitmind.course.service.ICourseCatalogueDraftService;
import com.fitmind.course.service.ICourseDraftService;
import com.fitmind.course.service.ICourseService;
import com.fitmind.course.service.ICourseCataSubjectService;
import com.fitmind.course.service.ICourseTeacherDraftService;
import com.fitmind.course.service.ISubjectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 课程草稿服务实现 —— 本项目最有含金量的一个类。
 *
 * <h3>草稿-发布机制的三条地基规则</h3>
 * <ol>
 *   <li><b>草稿表与正式表主键相同</b>（{@code course.id == course_draft.id}）</li>
 *   <li><b>上架后删草稿，下架后从正式表反向复制回草稿</b></li>
 *   <li><b>所有子表跟随主表一起同步，全在一个事务里</b></li>
 * </ol>
 *
 * <p>任意时刻，同一份课程数据只会在草稿表或正式表其中之一，
 * 不会出现"两边都有、内容还不一样"的脏状态。</p>
 */
@Slf4j
@Service
public class CourseDraftServiceImpl extends ServiceImpl<CourseDraftMapper, CourseDraft>
        implements ICourseDraftService {

    private final ICourseService courseService;
    private final CourseMapper courseMapper;
    private final CourseContentMapper courseContentMapper;
    private final CourseContentDraftMapper courseContentDraftMapper;
    private final RabbitMqHelper rabbitMqHelper;

    /** 目录草稿服务（上架时把训练目录同步到正式表） */
    private final ICourseCatalogueDraftService courseCatalogueDraftService;

    /** 课程教练草稿服务 */
    private final ICourseTeacherDraftService courseTeacherDraftService;

    /** 节点-题目关联服务（上架时把题目关联同步到正式表） */
    private final ICourseCataSubjectService courseCataSubjectService;

    /** 题目服务（删除课程时要清掉该课程的题目） */
    private final ISubjectService subjectService;

    /**
     * 显式构造器：目录服务那边要反向调用本服务的 {@code updateStep}，
     * 直接构造器注入会形成<b>循环依赖</b>（Spring 启动直接报错），
     * 所以这里用 {@code @Lazy} 注入代理对象打破环。
     */
    public CourseDraftServiceImpl(ICourseService courseService,
                                  CourseMapper courseMapper,
                                  CourseContentMapper courseContentMapper,
                                  CourseContentDraftMapper courseContentDraftMapper,
                                  RabbitMqHelper rabbitMqHelper,
                                  @Lazy ICourseCatalogueDraftService courseCatalogueDraftService,
                                  @Lazy ICourseTeacherDraftService courseTeacherDraftService,
                                  @Lazy ICourseCataSubjectService courseCataSubjectService,
                                  @Lazy ISubjectService subjectService) {
        this.courseService = courseService;
        this.courseMapper = courseMapper;
        this.courseContentMapper = courseContentMapper;
        this.courseContentDraftMapper = courseContentDraftMapper;
        this.rabbitMqHelper = rabbitMqHelper;
        this.courseCatalogueDraftService = courseCatalogueDraftService;
        this.courseTeacherDraftService = courseTeacherDraftService;
        this.courseCataSubjectService = courseCataSubjectService;
        this.subjectService = subjectService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CourseSaveVO save(CourseBaseInfoSaveDTO dto) {
        // 1. 重名校验（草稿 + 正式都要查，否则可能上架时才发现重名）
        checkNameDuplicate(dto.getName(), dto.getId());

        // 2. 保存草稿主表
        CourseDraft draft = BeanUtil.copyProperties(dto, CourseDraft.class);
        if (dto.getId() == null) {
            // 新建：进度从「基本信息」开始
            draft.setStep(CourseConstants.CourseStep.BASE_INFO);
            draft.setStatus(CourseStatus.NO_UP_SHELF.getStatus());
            draft.setMediaDuration(0);
            draft.setSectionNum(0);
            draft.setCanUpdate(true);
            draft.setCVersion(0);
            draft.setCreater(UserContext.getUser());
            save(draft);
        } else {
            // 更新
            CourseDraft exist = getById(dto.getId());
            if (exist == null) {
                throw new BizIllegalException(CourseErrorInfo.Msg.COURSE_NOT_EXISTS);
            }
            // ⚠️ step 只能前进不能后退：教练回来改一下基本信息，
            //    不能把已经填好的目录、视频进度重置掉
            int existStep = exist.getStep() == null ? 0 : exist.getStep();
            draft.setStep(Math.max(existStep, CourseConstants.CourseStep.BASE_INFO));
            draft.setUpdater(UserContext.getUser());
            updateById(draft);
        }

        // 3. 保存草稿正文（与草稿主表共用 id）
        CourseContentDraft contentDraft = BeanUtil.copyProperties(dto, CourseContentDraft.class);
        contentDraft.setId(draft.getId());
        if (courseContentDraftMapper.selectById(draft.getId()) == null) {
            contentDraft.setCreater(UserContext.getUser());
            courseContentDraftMapper.insert(contentDraft);
        } else {
            contentDraft.setUpdater(UserContext.getUser());
            courseContentDraftMapper.updateById(contentDraft);
        }

        log.info("保存课程草稿：id={}, name={}, step={}", draft.getId(), draft.getName(), draft.getStep());
        return CourseSaveVO.of(draft.getId(), draft.getName(), draft.getStep());
    }

    @Override
    public CourseBaseInfoVO getCourseBaseInfo(Long id) {
        // 1. 优先取草稿（编辑态的课程在草稿表）
        CourseDraft draft = getById(id);
        CourseBaseInfoVO vo;
        boolean fromDraft;
        if (draft != null) {
            vo = BeanUtil.copyProperties(draft, CourseBaseInfoVO.class);
            fromDraft = true;
        } else {
            // 2. 草稿没有，取正式表（已上架的课程）
            Course course = courseService.getById(id);
            if (course == null) {
                throw new BizIllegalException(CourseErrorInfo.Msg.COURSE_NOT_EXISTS);
            }
            vo = BeanUtil.copyProperties(course, CourseBaseInfoVO.class);
            fromDraft = false;
        }
        vo.setFromDraft(fromDraft);
        vo.setStatusDesc(CourseStatus.desc(vo.getStatus()));

        // 3. 正文同样优先取草稿
        if (fromDraft) {
            CourseContentDraft contentDraft = courseContentDraftMapper.selectById(id);
            if (contentDraft != null) {
                BeanUtil.copyProperties(contentDraft, vo);
            }
        } else {
            CourseContent content = courseContentMapper.selectById(id);
            if (content != null) {
                BeanUtil.copyProperties(content, vo);
            }
        }
        return vo;
    }

    @Override
    public void updateStep(Long id, Integer step) {
        CourseDraft draft = getById(id);
        if (draft == null || step == null) {
            return;
        }
        CourseDraft update = new CourseDraft().setId(id);
        // 版本号递增（乐观锁，防止并发编辑互相覆盖）
        update.setCVersion(draft.getCVersion() == null ? 1 : draft.getCVersion() + 1);

        int current = draft.getStep() == null ? 0 : draft.getStep();
        // ★ 只能前进不能后退
        update.setStep(Math.max(current, step));

        // 走到「目录」这一步时，顺带刷新训练动作数量
        if (step == CourseConstants.CourseStep.CATALOGUE) {
            update.setSectionNum(courseCatalogueDraftService.totalSectionNums(id));
        }
        updateById(update);
        log.debug("推进课程填写进度：courseId={}, {} -> {}", id, current, update.getStep());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void upShelf(Long id) {
        // 1. 取草稿与正式数据，判断是否首次上架
        CourseDraft draft = getById(id);
        Course course = courseService.getById(id);
        boolean isFirstUpShelf = (course == null);

        // 2. 校验（不通过直接抛业务异常，整个事务回滚）
        checkBeforeUpShelf(id);

        // 3. 计算视频总时长与训练动作数量
        //    时长按「训练日」分组累加：每个训练日的时长 = 该天所有动作的视频时长之和
        Map<Long, Integer> mediaDurations = courseCatalogueDraftService.calculateMediaDuration(id);
        int totalMediaDuration = mediaDurations.values().stream().mapToInt(Integer::intValue).sum();
        int sectionNum = courseCatalogueDraftService.totalSectionNums(id);

        // 4. 组装要写入正式表的数据
        CourseContentDraft contentDraft = courseContentDraftMapper.selectById(id);
        CourseContent contentToShelf = BeanUtil.copyProperties(contentDraft, CourseContent.class);

        Course courseToShelf = BeanUtil.copyProperties(draft, Course.class);
        courseToShelf.setMediaDuration(totalMediaDuration);
        courseToShelf.setSectionNum(sectionNum);
        // 4.1 上架时间与状态
        courseToShelf.setPublishTime(LocalDateTime.now());
        courseToShelf.setStatus(CourseStatus.SHELF.getStatus());
        // 4.2 发布次数累加
        int publishTimes = isFirstUpShelf ? 1
                : (course.getPublishTimes() == null ? 1 : course.getPublishTimes() + 1);
        courseToShelf.setPublishTimes(publishTimes);
        // 4.3 初始评分（M3 引入学员评价后由评价数据计算）
        courseToShelf.setScore(courseToShelf.getScore() == null ? 50 : courseToShelf.getScore());

        // 5. 同步子表到正式表（顺序：教练 → 目录 → 正文 → 基本信息）
        //    每个 copyToShelf 内部都会把对应的草稿删掉
        courseTeacherDraftService.copyToShelf(id, isFirstUpShelf);
        courseCatalogueDraftService.copyToShelf(id, isFirstUpShelf);
        courseCataSubjectService.copyToShelf(id);

        // 6. 同步正文与基本信息
        if (isFirstUpShelf) {
            courseService.save(courseToShelf);
            courseContentMapper.insert(contentToShelf);
            log.info("课程首次上架：id={}, name={}, 动作数={}, 总时长={}秒",
                    id, draft.getName(), sectionNum, totalMediaDuration);
        } else {
            courseService.updateById(courseToShelf);
            courseContentMapper.updateById(contentToShelf);
            log.info("课程重新上架：id={}, name={}, 第 {} 次发布, 动作数={}, 总时长={}秒",
                    id, draft.getName(), publishTimes, sectionNum, totalMediaDuration);
        }

        // 7. 删除课程与正文的草稿（上架后数据只保留在正式表）
        removeById(id);
        courseContentDraftMapper.deleteById(id);

        // 6. 异步通知搜索服务同步 ES 索引（失败不影响上架结果）
        //    ⚠️ 必须用 sendAfterCommit：消息若在事务提交前发出，fm-search 拿着 id
        //    回头查时会查不到刚写入正式表的数据（M2-3 验收时踩过，时快时慢极难排查）
        rabbitMqHelper.sendAfterCommit(MqConstants.Exchange.COURSE_EXCHANGE,
                MqConstants.Key.COURSE_UP_KEY, id);
    }

    @Override
    public void checkBeforeUpShelf(Long id) {
        CourseDraft draft = getById(id);
        Course course = courseService.getById(id);

        // 1. 幂等：草稿为空但正式存在 → 说明已经上架了
        if (draft == null && course != null) {
            throw new BizIllegalException(CourseErrorInfo.Msg.COURSE_UP_SHELF_ALREADY);
        }
        // 2. 两边都没有 → 课程不存在
        if (draft == null) {
            throw new BizIllegalException(CourseErrorInfo.Msg.COURSE_UP_SHELF_NOT_FOUND);
        }
        // 3. 填写步骤没走完不能上架
        if (draft.getStep() == null || draft.getStep() < CourseConstants.CourseStep.REQUIRED_FOR_SHELF) {
            throw new BizIllegalException(CourseErrorInfo.Msg.COURSE_UP_SHELF_INCOMPLETE);
        }
        // 4. 只有「待上架」或「已下架」的课程能上架
        if (course != null && !CourseStatus.DOWN_SHELF.equals(course.getStatus())) {
            throw new BizIllegalException(CourseErrorInfo.Msg.COURSE_UP_SHELF_STATE_WRONG);
        }
        // 5. 报名结束时间不能已过期
        if (draft.getEnrollEndTime() != null && draft.getEnrollEndTime().isBefore(LocalDateTime.now())) {
            throw new BizIllegalException(CourseErrorInfo.Msg.COURSE_UP_SHELF_ENROLL_ILLEGAL);
        }
        // 6. 首次上架要做重名校验（正式表里不能有同名课程）
        if (course == null && courseMapper.countSameName(draft.getName()) > 0) {
            throw new BizIllegalException(CourseErrorInfo.Msg.COURSE_NAME_EXISTS);
        }
        // 7. ★ 训练目录完整性：每个训练动作都必须绑定视频
        courseCatalogueDraftService.checkCataInfoImplated(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void downShelf(Long id) {
        // 1. 只有已上架的课程能下架
        Course course = courseService.getById(id);
        if (course == null || !CourseStatus.SHELF.equals(course.getStatus())) {
            throw new BizIllegalException(CourseErrorInfo.Msg.COURSE_DOWN_SHELF_FAILED);
        }
        // 2. 先改正式表状态
        courseService.updateStatus(id, CourseStatus.DOWN_SHELF.getStatus());
        // 3. 反向复制回草稿表（这样教练下次编辑不用重新录入）
        //    课程 → 正文 → 训练目录 → 课程教练，顺序与上架时相反
        int n1 = baseMapper.insertFromCourse(id);
        int n2 = courseContentDraftMapper.insertFromCourseContent(id);
        courseCatalogueDraftService.copyFromShelf(id);
        courseTeacherDraftService.copyFromShelf(id);
        courseCataSubjectService.copyFromShelf(id);
        if (n1 != 1 || n2 != 1) {
            // 复制失败必须整体回滚，否则会出现「状态改了但草稿没生成」的半截状态
            throw new BizIllegalException(CourseErrorInfo.Msg.COURSE_DOWN_SHELF_FAILED);
        }
        log.info("课程下架：id={}, name={}", id, course.getName());

        // 4. 异步通知搜索服务删除索引（同样等事务提交后再发，理由同上）
        rabbitMqHelper.sendAfterCommit(MqConstants.Exchange.COURSE_EXCHANGE,
                MqConstants.Key.COURSE_DOWN_KEY, id);
    }

    @Override
    public PageDTO<CoursePageVO> queryForPage(CoursePageQuery query) {
        LambdaQueryWrapper<CourseDraft> wrapper = new LambdaQueryWrapper<CourseDraft>()
                .like(isNotBlank(query.getName()), CourseDraft::getName, query.getName())
                .eq(query.getCourseType() != null, CourseDraft::getCourseType, query.getCourseType())
                .eq(query.getStatus() != null, CourseDraft::getStatus, query.getStatus())
                .eq(query.getFirstCateId() != null, CourseDraft::getFirstCateId, query.getFirstCateId());
        var page = page(query.toMpPageDefaultSortByCreateTimeDesc(), wrapper);
        return PageDTO.of(page, this::toVO);
    }

    @Override
    public NameExistVO checkName(String name, Long id) {
        // 草稿表（排除自己）+ 正式表，任一存在即算重名
        long draftCount = count(new LambdaQueryWrapper<CourseDraft>()
                .eq(CourseDraft::getName, name)
                .ne(id != null, CourseDraft::getId, id));
        if (draftCount > 0) {
            return NameExistVO.of(true);
        }
        // 正式表：如果命中的正是自己（id 相同），不算重名
        Course exist = courseService.getOne(new LambdaQueryWrapper<Course>()
                .eq(Course::getName, name)
                .last("LIMIT 1"));
        boolean duplicated = exist != null && (id == null || !exist.getId().equals(id));
        return NameExistVO.of(duplicated);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        // 草稿表没有 deleted 字段，是物理删除；正式表按项目当前约定也走物理删除
        removeById(id);
        courseContentDraftMapper.deleteById(id);
        courseService.removeById(id);
        courseContentMapper.deleteById(id);
        // 子表（训练目录 / 题目关联 / 题目）一并清理。
        // 这三张表都只有 course_id 一个锚点，课程没了它们就是孤儿数据——
        // 留着不会报错，但管理端按 courseId 查不到、按表全量查又能看见，
        // 属于最难受的一类脏数据。
        courseCatalogueDraftService.deleteByCourseId(id);
        courseCataSubjectService.deleteByCourseId(id);
        subjectService.deleteByCourseId(id);
        // ⚠️ 教练关联也要清！之前漏了这一步，导致 course_teacher 累计 268 条孤儿，
        //    而且 course 是自增 id（会复用）→ 新课程可能"继承"旧课的教练
        courseTeacherDraftService.deleteByCourseId(id);

        // ★ 异步通知搜索服务删除索引。
        //   ⚠️ 这一步原本是<b>没有</b>的 —— 于是直接删除一个"已上架"的课程时，
        //      数据库里没了、ES 里还留着索引，用户<b>仍能搜到一门已经不存在的课</b>
        //      （2026-09-14 在收尾检查里发现：ES 有 2 条孤儿文档，DB 里查无此课）。
        //   ★ 复用 course.down 而不是新建 course.delete：
        //      对搜索侧来说这两个动作的后果<b>完全一样</b>（把文档从索引里移除），
        //      而 course.down 的队列与消费者都已存在，不需要再建一套。
        //      （MqConstants 里的 COURSE_DELETE_KEY 因此保持未使用状态，已加注释说明。）
        //   用 sendAfterCommit：事务没提交就发消息，消费端可能查到一个"即将被删"的课程。
        rabbitMqHelper.sendAfterCommit(MqConstants.Exchange.COURSE_EXCHANGE,
                MqConstants.Key.COURSE_DOWN_KEY, id);

        log.info("删除课程：id={}（含训练目录、题目关联与题目，已通知搜索服务删除索引）", id);
    }

    // ==================== 内部方法 ====================

    private void checkNameDuplicate(String name, Long excludeId) {
        long draftCount = count(new LambdaQueryWrapper<CourseDraft>()
                .eq(CourseDraft::getName, name)
                .ne(excludeId != null, CourseDraft::getId, excludeId));
        if (draftCount > 0) {
            throw new BizIllegalException(CourseErrorInfo.Msg.COURSE_NAME_EXISTS);
        }
        if (courseMapper.countSameName(name) > 0) {
            throw new BizIllegalException(CourseErrorInfo.Msg.COURSE_NAME_EXISTS);
        }
    }

    private CoursePageVO toVO(CourseDraft d) {
        CoursePageVO vo = new CoursePageVO();
        vo.setId(d.getId());
        vo.setName(d.getName());
        vo.setCourseType(d.getCourseType());
        vo.setCoverUrl(d.getCoverUrl());
        vo.setThirdCateId(d.getThirdCateId());
        vo.setFree(d.getFree());
        vo.setPrice(d.getPrice());
        vo.setStatus(d.getStatus());
        vo.setStatusDesc(CourseStatus.desc(d.getStatus()));
        vo.setStep(d.getStep());
        vo.setSectionNum(d.getSectionNum());
        vo.setMediaDuration(d.getMediaDuration());
        vo.setPublishTime(d.getPublishTime());
        vo.setPublishTimes(d.getPublishTime() == null ? 0 : 1);
        vo.setCreateTime(d.getCreateTime());
        vo.setUpdateTime(d.getUpdateTime());
        return vo;
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
