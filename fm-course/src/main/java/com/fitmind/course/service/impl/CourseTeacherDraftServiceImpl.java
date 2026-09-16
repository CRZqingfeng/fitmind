package com.fitmind.course.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.api.client.user.UserClient;
import com.fitmind.api.dto.user.CoachDTO;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.utils.UserContext;
import com.fitmind.course.constants.CourseConstants;
import com.fitmind.course.constants.CourseErrorInfo;
import com.fitmind.course.domain.dto.CourseTeacherSaveDTO;
import com.fitmind.course.domain.po.Course;
import com.fitmind.course.domain.po.CourseTeacher;
import com.fitmind.course.domain.po.CourseTeacherDraft;
import com.fitmind.course.domain.vo.CourseTeacherVO;
import com.fitmind.course.mapper.CourseTeacherDraftMapper;
import com.fitmind.course.mapper.CourseTeacherMapper;
import com.fitmind.course.service.ICourseDraftService;
import com.fitmind.course.service.ICourseTeacherDraftService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 课程教练服务实现。
 */
@Slf4j
@Service
public class CourseTeacherDraftServiceImpl
        extends ServiceImpl<CourseTeacherDraftMapper, CourseTeacherDraft>
        implements ICourseTeacherDraftService {

    private final ICourseDraftService courseDraftService;
    private final CourseTeacherMapper courseTeacherMapper;
    private final UserClient userClient;

    public CourseTeacherDraftServiceImpl(ICourseDraftService courseDraftService,
                                         CourseTeacherMapper courseTeacherMapper,
                                         UserClient userClient) {
        this.courseDraftService = courseDraftService;
        this.courseTeacherMapper = courseTeacherMapper;
        this.userClient = userClient;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(CourseTeacherSaveDTO dto) {
        // 1. 课程必须存在（且要有草稿——只有草稿态才能编辑教练）
        if (courseDraftService.getById(dto.getId()) == null) {
            throw new BizIllegalException(CourseErrorInfo.Msg.TEACHER_SAVE_BEFORE_COURSE);
        }
        // 2. 全量覆盖：先删旧教练
        baseMapper.deleteByCourseId(dto.getId());

        // 3. 再插入新的
        if (!CollectionUtils.isEmpty(dto.getTeachers())) {
            List<CourseTeacherDraft> list = new ArrayList<>();
            int index = 1;
            for (CourseTeacherSaveDTO.TeacherInfo t : dto.getTeachers()) {
                CourseTeacherDraft draft = new CourseTeacherDraft();
                draft.setId(IdUtil.getSnowflakeNextId());
                draft.setCourseId(dto.getId());
                draft.setTeacherId(t.getId());
                draft.setIsShow(Boolean.FALSE.equals(t.getIsShow()) ? 0 : 1);
                draft.setCIndex(index++);
                draft.setCreater(UserContext.getUser());
                list.add(draft);
            }
            saveBatch(list);
        }

        // 4. 推进填写进度到「教练」（这是最后一步，走完才能上架）
        courseDraftService.updateStep(dto.getId(), CourseConstants.CourseStep.TEACHER);
        log.info("保存课程教练：courseId={}, 教练 {} 位", dto.getId(),
                dto.getTeachers() == null ? 0 : dto.getTeachers().size());
    }

    @Override
    public List<CourseTeacherVO> queryTeacherOfCourse(Long courseId, Boolean see) {
        // 1. 取教练关联数据：用户端优先看已上架的
        List<CourseTeacherDraft> drafts;
        if (Boolean.TRUE.equals(see)) {
            List<CourseTeacher> shelfList = courseTeacherMapper.selectList(
                    new LambdaQueryWrapper<CourseTeacher>().eq(CourseTeacher::getCourseId, courseId));
            if (!CollectionUtils.isEmpty(shelfList)) {
                drafts = shelfList.stream()
                        .map(t -> BeanUtil.copyProperties(t, CourseTeacherDraft.class))
                        .collect(Collectors.toList());
                return fillCoachInfo(drafts);
            }
        }
        drafts = list(new LambdaQueryWrapper<CourseTeacherDraft>()
                .eq(CourseTeacherDraft::getCourseId, courseId)
                .orderByAsc(CourseTeacherDraft::getCIndex));
        return fillCoachInfo(drafts);
    }

    /** 从 fm-user 补齐教练的姓名、头像、简介 */
    private List<CourseTeacherVO> fillCoachInfo(List<CourseTeacherDraft> drafts) {
        if (CollectionUtils.isEmpty(drafts)) {
            return new ArrayList<>();
        }
        List<Long> teacherIds = drafts.stream()
                .map(CourseTeacherDraft::getTeacherId)
                .collect(Collectors.toList());

        Map<Long, CoachDTO> coachMap;
        try {
            List<CoachDTO> coaches = userClient.queryCoaches(teacherIds);
            coachMap = CollectionUtils.isEmpty(coaches) ? Map.of()
                    : coaches.stream().collect(Collectors.toMap(CoachDTO::getId, Function.identity(), (a, b) -> a));
        } catch (Exception e) {
            // 用户服务不可用时不阻断：至少把教练 id 和展示状态返回，前端可以兜底显示
            log.error("查询教练信息失败，courseTeacherIds={}", teacherIds, e);
            coachMap = Map.of();
        }

        List<CourseTeacherVO> result = new ArrayList<>();
        for (CourseTeacherDraft d : drafts) {
            CourseTeacherVO vo = new CourseTeacherVO();
            vo.setId(d.getTeacherId());
            vo.setIsShow(d.getIsShow() == null || d.getIsShow() == 1);
            CoachDTO coach = coachMap.get(d.getTeacherId());
            if (coach != null) {
                vo.setName(coach.getName());
                vo.setIcon(coach.getIcon());
                vo.setPhoto(coach.getPhoto());
                vo.setIntro(coach.getIntro());
            }
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void copyToShelf(Long courseId, Boolean isFirstShelf) {
        List<CourseTeacherDraft> drafts = list(new LambdaQueryWrapper<CourseTeacherDraft>()
                .eq(CourseTeacherDraft::getCourseId, courseId));
        // 教练不是必填项，没有就跳过（不像目录那样必须有）
        if (CollectionUtils.isEmpty(drafts)) {
            return;
        }
        // 1. 先清空正式表的旧教练
        courseTeacherMapper.delete(new LambdaQueryWrapper<CourseTeacher>()
                .eq(CourseTeacher::getCourseId, courseId));
        // 2. 全量插入
        for (CourseTeacherDraft d : drafts) {
            CourseTeacher t = BeanUtil.copyProperties(d, CourseTeacher.class);
            t.setId(d.getId());
            t.setDeleted(0);
            courseTeacherMapper.insert(t);
        }
        // 3. 删除草稿
        baseMapper.deleteByCourseId(courseId);
        log.info("课程教练上架：courseId={}, 共 {} 位", courseId, drafts.size());
    }

    @Override
    public void copyFromShelf(Long courseId) {
        int n = baseMapper.insertFromCourseTeacher(courseId);
        log.info("课程教练回到草稿：courseId={}, 共 {} 位", courseId, n);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByCourseId(Long courseId) {
        // 草稿表（本类继承的 ServiceImpl 对应的表）
        remove(new LambdaQueryWrapper<CourseTeacherDraft>()
                .eq(CourseTeacherDraft::getCourseId, courseId));
        // 正式表：课程若是上架状态，关联在这里
        int n = courseTeacherMapper.delete(new LambdaQueryWrapper<CourseTeacher>()
                .eq(CourseTeacher::getCourseId, courseId));
        log.info("删除课程教练关联：courseId={}（正式表 {} 条）", courseId, n);
    }
}
