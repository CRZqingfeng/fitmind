package com.fitmind.course.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.common.constants.MqConstants;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.mq.message.PointsEventMessage;
import com.fitmind.common.utils.RabbitMqHelper;
import com.fitmind.common.utils.UserContext;
import com.fitmind.course.constants.CourseErrorInfo;
import com.fitmind.course.domain.dto.PracticeSubmitDTO;
import com.fitmind.course.domain.dto.SubjectFormDTO;
import com.fitmind.course.domain.po.CourseCataSubject;
import com.fitmind.course.domain.po.CourseCataSubjectDraft;
import com.fitmind.course.domain.po.Subject;
import com.fitmind.course.domain.query.SubjectPageQuery;
import com.fitmind.course.domain.vo.PracticeResultVO;
import com.fitmind.course.domain.vo.SubjectSimpleVO;
import com.fitmind.course.domain.vo.SubjectVO;
import com.fitmind.course.mapper.CourseCataSubjectDraftMapper;
import com.fitmind.course.mapper.CourseCataSubjectMapper;
import com.fitmind.course.mapper.SubjectMapper;
import com.fitmind.course.service.ICourseDraftService;
import com.fitmind.course.service.ISubjectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 动作小测题目服务实现。
 *
 * <p>本类有两件事值得单独说：</p>
 * <ol>
 *   <li><b>题型校验</b>：选择题的答案必须落在选项范围内、判断题答案只能是 1/2、
 *       单选题不能有多个答案。这些是"跨字段"约束，Bean Validation 表达不了，
 *       只能在服务端做。校验放在这里而不是前端——前端永远不可信。</li>
 *   <li><b>判分在服务端</b>：学员拉题时答案不下发，提交后服务端比对，
 *       答完才把正确答案和解析一起返回。</li>
 * </ol>
 */
@Slf4j
@Service
public class SubjectServiceImpl extends ServiceImpl<SubjectMapper, Subject> implements ISubjectService {

    /** 题型 */
    private static final int TYPE_SINGLE = 1;
    private static final int TYPE_JUDGEMENT = 4;

    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_ANALYSIS_LENGTH = 500;
    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 10;
    private static final int DEFAULT_SCORE = 5;

    private final ICourseDraftService courseDraftService;
    private final CourseCataSubjectMapper courseCataSubjectMapper;
    private final CourseCataSubjectDraftMapper courseCataSubjectDraftMapper;
    private final RabbitMqHelper rabbitMqHelper;

    public SubjectServiceImpl(ICourseDraftService courseDraftService,
                              CourseCataSubjectMapper courseCataSubjectMapper,
                              CourseCataSubjectDraftMapper courseCataSubjectDraftMapper,
                              RabbitMqHelper rabbitMqHelper) {
        this.courseDraftService = courseDraftService;
        this.courseCataSubjectMapper = courseCataSubjectMapper;
        this.courseCataSubjectDraftMapper = courseCataSubjectDraftMapper;
        this.rabbitMqHelper = rabbitMqHelper;
    }

    // ==================== 题目 CRUD（教练 / 管理端） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveSubject(SubjectFormDTO dto) {
        // 1. 跨字段校验（题型 / 选项 / 答案）
        validate(dto);

        // 2. 课程必须存在
        if (courseDraftService.getById(dto.getCourseId()) == null) {
            throw new BizIllegalException(CourseErrorInfo.Msg.SUBJECT_SAVE_BEFORE_COURSE);
        }

        Subject subject;
        if (dto.getId() == null) {
            subject = new Subject()
                    .setCourseId(dto.getCourseId())
                    .setAnswerTimes(0)
                    .setCorrectTimes(0)
                    .setCreater(UserContext.getUser());
            fill(subject, dto);
            save(subject);
            log.info("新增题目：id={}, courseId={}, 题型={}", subject.getId(), dto.getCourseId(), dto.getSubjectType());
        } else {
            subject = getById(dto.getId());
            if (subject == null) {
                throw new BizIllegalException(CourseErrorInfo.Msg.SUBJECT_NOT_EXISTS);
            }
            // 不允许把题目改挂到别的课程上：关联数据是按 courseId 组织的，
            // 改课程会让 course_cata_subject 里的记录指向一个不属于它的课程
            if (!subject.getCourseId().equals(dto.getCourseId())) {
                throw new BizIllegalException(CourseErrorInfo.Msg.CATA_SUBJECT_SUBJECT_NOT_IN_COURSE);
            }
            fill(subject, dto);
            subject.setUpdater(UserContext.getUser());
            updateById(subject);
            log.info("修改题目：id={}", subject.getId());
        }
        return subject.getId();
    }

    @Override
    public PageDTO<SubjectVO> querySubjectPage(SubjectPageQuery query) {
        LambdaQueryWrapper<Subject> wrapper = new LambdaQueryWrapper<Subject>()
                .eq(query.getCourseId() != null, Subject::getCourseId, query.getCourseId())
                .like(query.getName() != null && !query.getName().isBlank(), Subject::getName, query.getName())
                .eq(query.getSubjectType() != null, Subject::getSubjectType, query.getSubjectType())
                .eq(query.getDifficulty() != null, Subject::getDifficulty, query.getDifficulty())
                .orderByDesc(Subject::getId);
        Page<Subject> page = page(query.toMpPageDefaultSortByCreateTimeDesc(), wrapper);
        return PageDTO.of(page, this::toVO);
    }

    @Override
    public SubjectVO querySubjectDetail(Long id) {
        Subject subject = getById(id);
        if (subject == null) {
            throw new BizIllegalException(CourseErrorInfo.Msg.SUBJECT_NOT_EXISTS);
        }
        return toVO(subject);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSubject(Long id) {
        Subject subject = getById(id);
        if (subject == null) {
            throw new BizIllegalException(CourseErrorInfo.Msg.SUBJECT_NOT_EXISTS);
        }
        // 被关联的题目不能删——否则学员做小测时会在"题目列表"里看到一条查不到的题目
        List<Long> referenced = new ArrayList<>();
        referenced.addAll(courseCataSubjectMapper.queryReferencedSubjectIds(List.of(id)));
        referenced.addAll(courseCataSubjectDraftMapper.queryReferencedSubjectIds(List.of(id)));
        if (!referenced.isEmpty()) {
            throw new BizIllegalException(
                    String.format(CourseErrorInfo.Msg.SUBJECT_IN_USE, subject.getName()));
        }
        removeById(id);
        log.info("删除题目：id={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByCourseId(Long courseId) {
        int n = baseMapper.delete(new LambdaQueryWrapper<Subject>().eq(Subject::getCourseId, courseId));
        if (n > 0) {
            log.info("删除课程题目：courseId={}, 共 {} 道", courseId, n);
        }
    }

    // ==================== 学员侧：拉题 + 判分 ====================

    @Override
    public List<SubjectSimpleVO> queryPractice(Long cataId) {
        return listSubjectsOfCata(cataId).stream().map(s -> {
            SubjectSimpleVO vo = new SubjectSimpleVO();
            vo.setId(s.getId());
            vo.setName(s.getName());
            vo.setSubjectType(s.getSubjectType());
            vo.setDifficulty(s.getDifficulty());
            vo.setOptions(s.getOptions());
            vo.setScore(s.getScore());
            // ⚠️ 这里<b>故意不设置</b> answer / analysis：答案要等提交后才知道
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PracticeResultVO submitPractice(PracticeSubmitDTO dto, Long userId) {
        if (CollectionUtils.isEmpty(dto.getAnswers())) {
            throw new BizIllegalException(CourseErrorInfo.Msg.PRACTICE_ANSWERS_EMPTY);
        }
        // 1. 该节点的题目（含答案）
        List<Subject> subjects = listSubjectsOfCata(dto.getCataId());
        if (CollectionUtils.isEmpty(subjects)) {
            throw new BizIllegalException(CourseErrorInfo.Msg.CATA_SUBJECT_EMPTY);
        }
        Map<Long, Subject> subjectMap = subjects.stream()
                .collect(Collectors.toMap(Subject::getId, Function.identity(), (a, b) -> a));
        Long courseId = subjects.get(0).getCourseId();

        // 2. 逐题判分
        int score = 0;
        int totalScore = subjects.stream().mapToInt(this::scoreOf).sum();
        int correctCount = 0;
        Set<Long> answered = new HashSet<>();
        List<PracticeResultVO.Detail> details = new ArrayList<>();
        for (PracticeSubmitDTO.AnswerItem item : dto.getAnswers()) {
            // 不属于本节点的题目、重复提交同一题：直接跳过（不报错，避免整份提交失败）
            Subject subject = subjectMap.get(item.getSubjectId());
            if (subject == null || !answered.add(item.getSubjectId())) {
                continue;
            }
            boolean correct = isCorrect(subject, item.getAnswer());
            if (correct) {
                score += scoreOf(subject);
                correctCount++;
            }
            // 统计是「累加」语义：每次提交都算一次作答（与幂等的积分不同）
            baseMapper.increaseTimes(subject.getId(), 1, correct ? 1 : 0);

            PracticeResultVO.Detail detail = new PracticeResultVO.Detail();
            detail.setSubjectId(subject.getId());
            detail.setName(subject.getName());
            detail.setCorrect(correct);
            detail.setYourAnswer(normalizeToText(item.getAnswer()));
            detail.setRightAnswer(subject.getAnswer());
            detail.setAnalysis(subject.getAnalysis());
            details.add(detail);
        }

        // 3. 答对的题发积分事件。
        //    ★ 幂等 bizId 形如 courseId:cataId:subjectId —— 按"题"而不是按"节点"：
        //      · 一个动作 3 道题答对 2 道 → 能拿 2 份分（粒度细）
        //      · 同一道题重复答对 → 被 points_record 的 (user_id,type,bizId) 唯一索引挡住
        //    这里不去判断"是不是首次答对"：判断要靠查库，而 MQ 重复投递本来就该由消费侧幂等兜住
        for (PracticeResultVO.Detail detail : details) {
            if (Boolean.TRUE.equals(detail.getCorrect())) {
                rabbitMqHelper.sendAfterCommit(MqConstants.Exchange.LEARNING_EXCHANGE,
                        MqConstants.Key.PRACTICE_ANSWERED,
                        PointsEventMessage.of(userId,
                                courseId + ":" + dto.getCataId() + ":" + detail.getSubjectId()));
            }
        }

        PracticeResultVO result = new PracticeResultVO();
        result.setScore(score);
        result.setTotalScore(totalScore);
        result.setCorrectCount(correctCount);
        result.setTotalCount(subjects.size());
        result.setDetails(details);
        log.info("小测提交：userId={}, cataId={}, 得分 {}/{}, 答对 {}/{}",
                userId, dto.getCataId(), score, totalScore, correctCount, subjects.size());
        return result;
    }

    // ==================== 内部方法 ====================

    /**
     * 取某节点下的题目（按 c_index 顺序）。
     *
     * <p>优先正式表（课程已上架），为空则回退草稿表（教练在编辑期自己预览）——
     * 与目录、教练的 {@code see} 语义保持一致。</p>
     */
    private List<Subject> listSubjectsOfCata(Long cataId) {
        List<Long> subjectIds = new ArrayList<>();
        List<CourseCataSubject> shelf = courseCataSubjectMapper.selectList(
                new LambdaQueryWrapper<CourseCataSubject>()
                        .eq(CourseCataSubject::getCataId, cataId)
                        .orderByAsc(CourseCataSubject::getCIndex));
        if (!CollectionUtils.isEmpty(shelf)) {
            subjectIds = shelf.stream().map(CourseCataSubject::getSubjectId).collect(Collectors.toList());
        } else {
            List<CourseCataSubjectDraft> drafts = courseCataSubjectDraftMapper.selectList(
                    new LambdaQueryWrapper<CourseCataSubjectDraft>()
                            .eq(CourseCataSubjectDraft::getCataId, cataId)
                            .orderByAsc(CourseCataSubjectDraft::getCIndex));
            subjectIds = drafts.stream().map(CourseCataSubjectDraft::getSubjectId).collect(Collectors.toList());
        }
        if (subjectIds.isEmpty()) {
            throw new BizIllegalException(CourseErrorInfo.Msg.CATA_SUBJECT_EMPTY);
        }
        // listByIds 不保证顺序，按关联表的 c_index 重排
        Map<Long, Subject> map = listByIds(subjectIds).stream()
                .collect(Collectors.toMap(Subject::getId, Function.identity(), (a, b) -> a));
        List<Subject> result = new ArrayList<>();
        for (Long id : subjectIds) {
            Subject s = map.get(id);
            if (s != null) {
                result.add(s);
            }
        }
        return result;
    }

    /**
     * 判分：把「学员答案」和「正确答案」都规范化成有序去重的集合再比。
     *
     * <p>为什么用集合而不是字符串直接比：多选题的答案是<b>集合</b>语义——
     * 正确答案 "1,3"，学员填 "3,1" 或 "1,3,3" 都该算对。
     * 直接用字符串相等会把这些情况判错。</p>
     */
    private boolean isCorrect(Subject subject, String yourAnswer) {
        Set<Integer> yours = normalize(yourAnswer);
        if (yours.isEmpty()) {
            return false;
        }
        return yours.equals(new TreeSet<>(subject.parseAnswers()));
    }

    /** 规范化答案文本：去空白、排序、去重 —— 顺便回显给前端看的是"干净"的答案 */
    private String normalizeToText(String answer) {
        return normalize(answer).stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static Set<Integer> normalize(String answer) {
        Set<Integer> set = new TreeSet<>();
        if (answer == null) {
            return set;
        }
        for (String part : answer.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                set.add(Integer.parseInt(t));
            } catch (NumberFormatException ignored) {
                // 非数字的作答直接忽略：不因为一个乱填的答案让整份提交失败
            }
        }
        return set;
    }

    private int scoreOf(Subject subject) {
        return subject.getScore() == null ? 0 : subject.getScore();
    }

    /** 把 DTO 的字段填进实体（新增/修改共用） */
    private void fill(Subject subject, SubjectFormDTO dto) {
        subject.setName(dto.getName().trim());
        subject.setSubjectType(dto.getSubjectType());
        subject.setDifficulty(dto.getDifficulty());
        // 判断题没有选项：即使前端传了也强制清空，避免"看上去有选项但答案是 1/2"的混乱数据
        subject.setOptions(dto.getSubjectType() == TYPE_JUDGEMENT ? null : dto.getOptions());
        subject.setAnswer(normalizeToText(dto.getAnswer()));
        subject.setAnalysis(dto.getAnalysis());
        subject.setScore(dto.getScore() == null ? DEFAULT_SCORE : dto.getScore());
    }

    /**
     * 校验题型相关的字段约束。
     *
     * <p>这些规则<b>必须服务端做</b>：前端校验只影响体验，改个请求就能绕过，
     * 而错误的题目数据会让判分逻辑直接失效（比如答案 5 但只有 3 个选项）。</p>
     */
    private void validate(SubjectFormDTO dto) {
        Integer type = dto.getSubjectType();
        if (type == null || type < TYPE_SINGLE || type > TYPE_JUDGEMENT) {
            throw new BizIllegalException(CourseErrorInfo.Msg.SUBJECT_TYPE_ILLEGAL);
        }
        Integer difficulty = dto.getDifficulty();
        if (difficulty == null || difficulty < 1 || difficulty > 3) {
            throw new BizIllegalException(CourseErrorInfo.Msg.SUBJECT_DIFFICULTY_ILLEGAL);
        }
        if (dto.getName().trim().length() > MAX_NAME_LENGTH) {
            throw new BizIllegalException(CourseErrorInfo.Msg.SUBJECT_NAME_TOO_LONG);
        }
        if (dto.getAnalysis() != null && dto.getAnalysis().length() > MAX_ANALYSIS_LENGTH) {
            throw new BizIllegalException(CourseErrorInfo.Msg.SUBJECT_ANALYSIS_TOO_LONG);
        }

        Set<Integer> answers = normalize(dto.getAnswer());
        if (answers.isEmpty()) {
            throw new BizIllegalException(CourseErrorInfo.Msg.SUBJECT_ANSWER_EMPTY);
        }

        if (type == TYPE_JUDGEMENT) {
            // 判断题：1-正确 2-错误，只能选一个
            if (answers.size() != 1 || (!answers.contains(1) && !answers.contains(2))) {
                throw new BizIllegalException(CourseErrorInfo.Msg.SUBJECT_JUDGE_ANSWER_ILLEGAL);
            }
            return;
        }

        // 选择题（单选 / 多选 / 不定向）
        List<String> options = dto.getOptions();
        // ⚠️ 下界 2 也要校验：1 个选项的单选题没有干扰项，多选题更不可能选对，
        //    属于无意义的数据。验收时就是这条漏了（错误码文案写着 2~10，
        //    代码却只挡了「空」和「超过 10」）。
        if (CollectionUtils.isEmpty(options)
                || options.size() < MIN_OPTIONS
                || options.size() > MAX_OPTIONS) {
            throw new BizIllegalException(CourseErrorInfo.Msg.SUBJECT_OPTIONS_ILLEGAL);
        }
        for (String option : options) {
            if (option == null || option.isBlank()) {
                throw new BizIllegalException(CourseErrorInfo.Msg.SUBJECT_OPTIONS_ILLEGAL);
            }
        }
        int max = answers.stream().max(Integer::compareTo).orElse(0);
        if (max > options.size()) {
            throw new BizIllegalException(String.format(
                    CourseErrorInfo.Msg.SUBJECT_ANSWER_OUT_OF_RANGE, options.size()));
        }
        if (type == TYPE_SINGLE && answers.size() > 1) {
            throw new BizIllegalException(CourseErrorInfo.Msg.SUBJECT_SINGLE_ANSWER_MULTIPLE);
        }
    }

    private SubjectVO toVO(Subject s) {
        SubjectVO vo = new SubjectVO();
        vo.setId(s.getId());
        vo.setCourseId(s.getCourseId());
        vo.setName(s.getName());
        vo.setSubjectType(s.getSubjectType());
        vo.setDifficulty(s.getDifficulty());
        vo.setOptions(s.getOptions());
        vo.setAnswer(s.getAnswer());
        vo.setAnswers(s.parseAnswers());
        vo.setAnalysis(s.getAnalysis());
        vo.setScore(s.getScore());
        vo.setAnswerTimes(s.getAnswerTimes());
        vo.setCorrectTimes(s.getCorrectTimes());
        vo.setCreateTime(s.getCreateTime());
        return vo;
    }
}
