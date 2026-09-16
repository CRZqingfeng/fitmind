package com.fitmind.learning.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.api.client.course.CourseClient;
import com.fitmind.api.client.user.UserClient;
import com.fitmind.api.dto.course.CataSimpleDTO;
import com.fitmind.api.dto.course.CourseSimpleDTO;
import com.fitmind.api.dto.user.UserDTO;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.exceptions.BadRequestException;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.utils.UserContext;
import com.fitmind.learning.domain.dto.QuestionFormDTO;
import com.fitmind.learning.domain.po.InteractionQuestion;
import com.fitmind.learning.domain.po.InteractionReply;
import com.fitmind.learning.domain.query.QuestionAdminPageQuery;
import com.fitmind.learning.domain.query.QuestionPageQuery;
import com.fitmind.learning.domain.vo.QuestionAdminVO;
import com.fitmind.learning.domain.vo.QuestionVO;
import com.fitmind.learning.enums.QuestionStatus;
import com.fitmind.learning.mapper.InteractionQuestionMapper;
import com.fitmind.learning.mapper.InteractionReplyMapper;
import com.fitmind.learning.service.IInteractionQuestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 互动提问服务实现。
 *
 * <p>两个贯穿全类的约定：</p>
 * <ol>
 *   <li><b>匿名</b>：列表/详情里，匿名记录不查、不返回用户姓名与头像；
 *       但管理端（{@code forAdmin}）一律返回真实信息 —— 管理员必须知道是谁提的</li>
 *   <li><b>批量补齐</b>：用户、课程、训练目录名都走批量接口，避免 N+1 次跨服务调用</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InteractionQuestionServiceImpl extends ServiceImpl<InteractionQuestionMapper, InteractionQuestion>
        implements IInteractionQuestionService {

    private final InteractionReplyMapper replyMapper;

    private final UserClient userClient;

    private final CourseClient courseClient;

    @Override
    public void saveQuestion(QuestionFormDTO dto) {
        Long userId = UserContext.getUser();
        InteractionQuestion question = new InteractionQuestion()
                .setTitle(dto.getTitle())
                .setDescription(dto.getDescription())
                .setCourseId(dto.getCourseId())
                .setChapterId(dto.getChapterId())
                .setSectionId(dto.getSectionId())
                .setUserId(userId)
                .setAnonymity(Boolean.TRUE.equals(dto.getAnonymity()))
                .setAnswerTimes(0)
                // 新提问默认「未查看」，等管理端过一遍
                .setStatus(QuestionStatus.UN_CHECK.getValue())
                .setHidden(false);
        save(question);
    }

    @Override
    public PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query) {
        // 课程和动作至少要有一个限定 —— 否则就是全站问题列表，没有业务意义
        if (query.getCourseId() == null && query.getSectionId() == null) {
            throw new BadRequestException("课程id和训练动作id不能都为空");
        }
        Page<InteractionQuestion> page = lambdaQuery()
                .eq(Boolean.TRUE.equals(query.getOnlyMine()),
                        InteractionQuestion::getUserId, UserContext.getUser())
                .eq(query.getCourseId() != null, InteractionQuestion::getCourseId, query.getCourseId())
                .eq(query.getSectionId() != null, InteractionQuestion::getSectionId, query.getSectionId())
                .eq(InteractionQuestion::getHidden, false)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<InteractionQuestion> records = page.getRecords();
        if (records == null || records.isEmpty()) {
            return PageDTO.empty(page);
        }

        // 1.收集要查的 id：提问者（匿名的跳过）+ 最新回答
        Set<Long> userIds = new HashSet<>();
        Set<Long> latestAnswerIds = new HashSet<>();
        for (InteractionQuestion q : records) {
            if (!Boolean.TRUE.equals(q.getAnonymity()) && q.getUserId() != null) {
                userIds.add(q.getUserId());
            }
            if (q.getLatestAnswerId() != null) {
                latestAnswerIds.add(q.getLatestAnswerId());
            }
        }
        // 2.批量查最新回答（顺带把回答者也加进用户集合）
        Map<Long, InteractionReply> replyMap = new HashMap<>();
        if (!latestAnswerIds.isEmpty()) {
            for (InteractionReply r : replyMapper.selectBatchIds(latestAnswerIds)) {
                replyMap.put(r.getId(), r);
                if (!Boolean.TRUE.equals(r.getAnonymity()) && r.getUserId() != null) {
                    userIds.add(r.getUserId());
                }
            }
        }
        Map<Long, UserDTO> userMap = queryUserMap(userIds);

        // 3.组装
        List<QuestionVO> list = new ArrayList<>(records.size());
        for (InteractionQuestion q : records) {
            QuestionVO vo = BeanUtil.copyProperties(q, QuestionVO.class);
            if (!Boolean.TRUE.equals(q.getAnonymity())) {
                fillUser(vo, userMap.get(q.getUserId()));
            }
            InteractionReply latest = replyMap.get(q.getLatestAnswerId());
            if (latest != null) {
                vo.setLatestReplyContent(latest.getContent());
                if (!Boolean.TRUE.equals(latest.getAnonymity())) {
                    UserDTO author = userMap.get(latest.getUserId());
                    if (author != null) {
                        vo.setLatestReplyUser(author.getName());
                    }
                }
            }
            list.add(vo);
        }
        return PageDTO.of(page, list);
    }

    @Override
    public QuestionVO queryQuestionById(Long id) {
        InteractionQuestion question = getById(id);
        // 不存在、或被隐藏 → 用户端看不到
        if (question == null || Boolean.TRUE.equals(question.getHidden())) {
            return null;
        }
        QuestionVO vo = BeanUtil.copyProperties(question, QuestionVO.class);
        if (!Boolean.TRUE.equals(question.getAnonymity())) {
            UserDTO user = userClient.queryUserById(question.getUserId());
            fillUser(vo, user);
        }
        return vo;
    }

    @Override
    public PageDTO<QuestionAdminVO> queryQuestionPageAdmin(QuestionAdminPageQuery query) {
        Page<InteractionQuestion> page = lambdaQuery()
                .eq(query.getCourseId() != null, InteractionQuestion::getCourseId, query.getCourseId())
                .eq(query.getStatus() != null, InteractionQuestion::getStatus, query.getStatus())
                .gt(query.getBeginTime() != null, InteractionQuestion::getCreateTime, query.getBeginTime())
                .lt(query.getEndTime() != null, InteractionQuestion::getCreateTime, query.getEndTime())
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<InteractionQuestion> records = page.getRecords();
        if (records == null || records.isEmpty()) {
            return PageDTO.empty(page);
        }

        // 管理端：匿名也要查出真实用户，所以不跳过任何 user_id
        Set<Long> userIds = new HashSet<>();
        Set<Long> courseIds = new HashSet<>();
        Set<Long> cataIds = new HashSet<>();
        for (InteractionQuestion q : records) {
            userIds.add(q.getUserId());
            courseIds.add(q.getCourseId());
            cataIds.add(q.getChapterId());
            cataIds.add(q.getSectionId());
        }

        // ★ 三份数据各一次批量查询 —— 逐个查就是 3N 次跨服务调用的 N+1
        Map<Long, UserDTO> userMap = queryUserMap(userIds);
        Map<Long, String> courseNameMap = queryCourseNames(courseIds);
        Map<Long, String> cataNameMap = queryCataNames(cataIds);

        List<QuestionAdminVO> list = new ArrayList<>(records.size());
        for (InteractionQuestion q : records) {
            QuestionAdminVO vo = BeanUtil.copyProperties(q, QuestionAdminVO.class);
            UserDTO user = userMap.get(q.getUserId());
            if (user != null) {
                vo.setUserName(user.getName());
                vo.setUserIcon(user.getIcon());
            }
            vo.setCourseName(courseNameMap.get(q.getCourseId()));
            vo.setChapterName(cataNameMap.get(q.getChapterId()));
            vo.setSectionName(cataNameMap.get(q.getSectionId()));
            list.add(vo);
        }
        return PageDTO.of(page, list);
    }

    @Override
    public QuestionAdminVO queryQuestionByIdAdmin(Long id) {
        InteractionQuestion question = getById(id);
        if (question == null) {
            return null;
        }
        QuestionAdminVO vo = BeanUtil.copyProperties(question, QuestionAdminVO.class);
        UserDTO user = userClient.queryUserById(question.getUserId());
        if (user != null) {
            vo.setUserName(user.getName());
            vo.setUserIcon(user.getIcon());
        }
        vo.setCourseName(queryCourseNames(Set.of(question.getCourseId())).get(question.getCourseId()));
        Map<Long, String> cataNameMap = queryCataNames(
                Set.of(question.getChapterId(), question.getSectionId()));
        vo.setChapterName(cataNameMap.get(question.getChapterId()));
        vo.setSectionName(cataNameMap.get(question.getSectionId()));
        return vo;
    }

    @Override
    public void hiddenQuestion(Long id, Boolean hidden) {
        updateById(new InteractionQuestion().setId(id).setHidden(Boolean.TRUE.equals(hidden)));
    }

    @Override
    public void updateQuestion(Long id, QuestionFormDTO dto) {
        InteractionQuestion old = getById(id);
        if (old == null) {
            throw new BizIllegalException("问题不存在");
        }
        if (!old.getUserId().equals(UserContext.getUser())) {
            throw new BizIllegalException("无权修改他人的问题");
        }
        updateById(new InteractionQuestion()
                .setId(id)
                .setTitle(dto.getTitle())
                .setDescription(dto.getDescription())
                .setAnonymity(Boolean.TRUE.equals(dto.getAnonymity())));
    }

    @Override
    @Transactional
    public void deleteQuestion(Long id) {
        InteractionQuestion old = getById(id);
        if (old == null) {
            return;
        }
        if (!old.getUserId().equals(UserContext.getUser())) {
            throw new BizIllegalException("无权删除他人的问题");
        }
        // 物理删除：问题下的所有回答与评论一起清掉，避免留下无主的孤儿数据
        removeById(id);
        replyMapper.delete(new LambdaQueryWrapper<InteractionReply>()
                .eq(InteractionReply::getQuestionId, id));
    }

    /** 批量查用户（一次跨服务调用） */
    private Map<Long, UserDTO> queryUserMap(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<UserDTO> users = userClient.queryUserByIds(new ArrayList<>(userIds));
        if (users == null || users.isEmpty()) {
            return Map.of();
        }
        return users.stream().collect(Collectors.toMap(UserDTO::getId, u -> u, (a, b) -> a));
    }

    /** 批量查课程名 */
    private Map<Long, String> queryCourseNames(Set<Long> courseIds) {
        if (courseIds == null || courseIds.isEmpty()) {
            return Map.of();
        }
        List<CourseSimpleDTO> courses = courseClient.queryCourseSimpleList(new ArrayList<>(courseIds));
        if (courses == null || courses.isEmpty()) {
            return Map.of();
        }
        return courses.stream().collect(Collectors.toMap(CourseSimpleDTO::getId, CourseSimpleDTO::getName, (a, b) -> a));
    }

    /** 批量查训练日/动作名 */
    private Map<Long, String> queryCataNames(Set<Long> cataIds) {
        cataIds = cataIds.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (cataIds.isEmpty()) {
            return Map.of();
        }
        List<CataSimpleDTO> catas = courseClient.queryCataSimpleList(new ArrayList<>(cataIds));
        if (catas == null || catas.isEmpty()) {
            return Map.of();
        }
        return catas.stream().collect(Collectors.toMap(CataSimpleDTO::getId, CataSimpleDTO::getName, (a, b) -> a));
    }

    private void fillUser(QuestionVO vo, UserDTO user) {
        if (user != null) {
            vo.setUserName(user.getName());
            vo.setUserIcon(user.getIcon());
        }
    }
}
