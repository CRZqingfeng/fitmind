package com.fitmind.learning.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.api.client.course.CourseClient;
import com.fitmind.api.client.remark.RemarkClient;
import com.fitmind.api.client.user.UserClient;
import com.fitmind.api.dto.course.CataSimpleDTO;
import com.fitmind.api.dto.course.CourseSimpleDTO;
import com.fitmind.api.dto.user.UserDTO;
import com.fitmind.common.constants.MqConstants;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.exceptions.BadRequestException;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.utils.RabbitMqHelper;
import com.fitmind.common.utils.UserContext;
import com.fitmind.learning.domain.dto.NoteFormDTO;
import com.fitmind.learning.domain.po.Note;
import com.fitmind.learning.domain.query.NoteAdminPageQuery;
import com.fitmind.learning.domain.query.NotePageQuery;
import com.fitmind.learning.domain.vo.NoteAdminDetailVO;
import com.fitmind.learning.domain.vo.NoteAdminVO;
import com.fitmind.learning.domain.vo.NoteVO;
import com.fitmind.learning.mapper.NoteMapper;
import com.fitmind.common.mq.message.PointsEventMessage;
import com.fitmind.learning.service.INoteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 学习笔记服务实现。
 *
 * <p><b>采集的语义</b>（沿用常见实现）：不是"引用"，而是<b>复制一份私有副本</b> ——
 * 新行 {@code user_id = 采集者}、{@code author_id = 原作者}、{@code is_gathered = 1}、
 * {@code gathered_note_id = 原笔记id}、{@code is_private = 1}（强制私有）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteServiceImpl extends ServiceImpl<NoteMapper, Note> implements INoteService {

    private final UserClient userClient;

    private final CourseClient courseClient;

    private final RemarkClient remarkClient;

    private final RabbitMqHelper rabbitMqHelper;

    @Override
    @Transactional
    public void saveNote(NoteFormDTO dto) {
        Long userId = UserContext.getUser();
        Note note = new Note()
                .setUserId(userId)
                .setAuthorId(userId)
                .setCourseId(dto.getCourseId())
                .setChapterId(dto.getChapterId())
                .setSectionId(dto.getSectionId())
                .setNoteMoment(dto.getNoteMoment())
                .setContent(dto.getContent())
                .setIsPrivate(Boolean.TRUE.equals(dto.getIsPrivate()))
                .setIsGathered(false)
                .setHidden(false)
                .setLikedTimes(0);
        save(note);
        // 写笔记送积分：走 MQ，事务提交后再发（消息先到的话消费端拿不到这条记录）
        rabbitMqHelper.sendAfterCommit(MqConstants.Exchange.LEARNING_EXCHANGE,
                MqConstants.Key.WRITE_NOTE,
                PointsEventMessage.of(userId, note.getId().toString()));
    }

    @Override
    public void updateNote(NoteFormDTO dto) {
        if (dto.getId() == null) {
            throw new BadRequestException("笔记id不能为空");
        }
        Note old = getById(dto.getId());
        if (old == null) {
            throw new BizIllegalException("笔记不存在");
        }
        if (!old.getUserId().equals(UserContext.getUser())) {
            throw new BizIllegalException("无权修改他人的笔记");
        }
        Note update = new Note().setId(dto.getId()).setContent(dto.getContent());
        // 采集来的副本永远私有 —— 否则用户改一下就把别人的笔记"洗"成自己的公开内容了
        update.setIsPrivate(old.getIsGathered() || Boolean.TRUE.equals(dto.getIsPrivate()));
        updateById(update);
    }

    @Override
    public void removeMyNote(Long id) {
        Note note = getById(id);
        if (note == null) {
            return;
        }
        if (!note.getUserId().equals(UserContext.getUser())) {
            throw new BizIllegalException("无权删除他人的笔记");
        }
        removeById(id);
    }

    @Override
    public PageDTO<NoteVO> queryNotePage(NotePageQuery query) {
        Long courseId = query.getCourseId();
        Long sectionId = query.getSectionId();
        if (courseId == null && sectionId == null) {
            throw new BadRequestException("课程id和训练动作id不能都为空");
        }
        Long userId = UserContext.getUser();
        Page<Note> pageParam = new Page<>(query.getPageNo(), query.getPageSize());

        IPage<Note> result;
        if (Boolean.TRUE.equals(query.getOnlyMine())) {
            // 我的笔记：连采集来的副本一起看（按动作内的时间点升序，方便跟着视频复习）
            result = lambdaQuery()
                    .eq(Note::getUserId, userId)
                    .eq(Note::getHidden, false)
                    .eq(courseId != null, Note::getCourseId, courseId)
                    .eq(sectionId != null, Note::getSectionId, sectionId)
                    .orderByAsc(sectionId != null, Note::getNoteMoment)
                    .orderByDesc(sectionId == null, Note::getId)
                    .page(pageParam);
        } else {
            // 公共笔记：走自定义 SQL（EXISTS 标记"我是否采集过"，并从根上避免重复行）
            result = baseMapper.queryNotePageBySectionId(pageParam, userId, courseId, sectionId);
        }
        return PageDTO.of((Page<Note>) result, toVOList(result.getRecords()));
    }

    @Override
    @Transactional
    public void gatherNote(Long id) {
        Long userId = UserContext.getUser();
        Note source = getById(id);
        // 隐私的 / 被折叠的 / 不存在的都采集不了（否则就成了绕过隐私的入口）
        if (source == null || Boolean.TRUE.equals(source.getIsPrivate())
                || Boolean.TRUE.equals(source.getHidden())) {
            throw new BizIllegalException("笔记不存在");
        }
        // 幂等：已经采集过就别再复制一份（常见实现没做这层校验，重复点会插入多行副本）
        long exists = lambdaQuery()
                .eq(Note::getUserId, userId)
                .eq(Note::getGatheredNoteId, id)
                .count();
        if (exists > 0) {
            throw new BizIllegalException("已经采集过这条笔记");
        }
        Note copy = new Note()
                .setUserId(userId)
                .setAuthorId(source.getAuthorId())
                .setCourseId(source.getCourseId())
                .setChapterId(source.getChapterId())
                .setSectionId(source.getSectionId())
                .setNoteMoment(source.getNoteMoment())
                .setContent(source.getContent())
                // 采集来的副本强制私有：原作者的隐私设置不能被采集行为绕过
                .setIsPrivate(true)
                .setIsGathered(true)
                .setGatheredNoteId(source.getId())
                .setHidden(false)
                .setLikedTimes(0);
        save(copy);
        // 采集成功 +2 分（记在<b>采集者</b>名下，与常见实现一致；bizId 用副本 id，
        // 所以同一笔记被同一个人采集两次也只算一次 —— 上面已经拦住了重复采集）
        rabbitMqHelper.sendAfterCommit(MqConstants.Exchange.LEARNING_EXCHANGE,
                MqConstants.Key.NOTE_GATHERED,
                PointsEventMessage.of(userId, copy.getId().toString()));
    }

    @Override
    public void removeGatherNote(Long id) {
        // ⚠️ 入参 id 是「原笔记 id」：前端在公共列表上点"取消采集"时，手里只有原笔记的 id
        remove(new LambdaQueryWrapper<Note>()
                .eq(Note::getUserId, UserContext.getUser())
                .eq(Note::getGatheredNoteId, id));
    }

    @Override
    public PageDTO<NoteAdminVO> queryNotePageForAdmin(NoteAdminPageQuery query) {
        Page<Note> pageParam = new Page<>(query.getPageNo(), query.getPageSize());
        LambdaQueryWrapper<Note> wrapper = new QueryWrapper<Note>().lambda()
                .eq(query.getCourseId() != null, Note::getCourseId, query.getCourseId())
                .eq(query.getHidden() != null, Note::getHidden, query.getHidden())
                .ge(query.getBeginTime() != null, Note::getCreateTime, query.getBeginTime())
                .le(query.getEndTime() != null, Note::getCreateTime, query.getEndTime())
                .orderByDesc(Note::getCreateTime);
        // 自定义 SQL：附带「被采集次数」（子查询，不用 JOIN）
        IPage<Note> page = baseMapper.queryNotePage(pageParam, wrapper);
        List<Note> records = page.getRecords();
        if (records == null || records.isEmpty()) {
            return PageDTO.empty(pageParam);
        }

        // 三份数据各一次批量查询（用户 / 课程 / 训练目录）
        Set<Long> userIds = new HashSet<>();
        Set<Long> courseIds = new HashSet<>();
        Set<Long> cataIds = new HashSet<>();
        for (Note n : records) {
            userIds.add(n.getAuthorId());
            courseIds.add(n.getCourseId());
            cataIds.add(n.getChapterId());
            cataIds.add(n.getSectionId());
        }
        Map<Long, UserDTO> userMap = queryUserMap(userIds);
        Map<Long, String> courseNameMap = queryCourseNames(courseIds);
        Map<Long, String> cataNameMap = queryCataNames(cataIds);

        List<NoteAdminVO> list = new ArrayList<>(records.size());
        for (Note n : records) {
            NoteAdminVO vo = BeanUtil.copyProperties(n, NoteAdminVO.class);
            UserDTO author = userMap.get(n.getAuthorId());
            if (author != null) {
                vo.setAuthorName(author.getName());
            }
            vo.setCourseName(courseNameMap.get(n.getCourseId()));
            vo.setChapterName(cataNameMap.get(n.getChapterId()));
            vo.setSectionName(cataNameMap.get(n.getSectionId()));
            list.add(vo);
        }
        return PageDTO.of(pageParam, list);
    }

    @Override
    public NoteAdminDetailVO queryNoteDetailForAdmin(Long id) {
        Note note = getById(id);
        if (note == null) {
            return null;
        }
        NoteAdminDetailVO vo = BeanUtil.copyProperties(note, NoteAdminDetailVO.class);
        UserDTO author = userClient.queryUserById(note.getAuthorId());
        if (author != null) {
            vo.setAuthorName(author.getName());
            vo.setAuthorPhone(author.getCellPhone());
        }
        vo.setCourseName(queryCourseNames(Set.of(note.getCourseId())).get(note.getCourseId()));
        Map<Long, String> cataNameMap = queryCataNames(Set.of(note.getChapterId(), note.getSectionId()));
        vo.setChapterName(cataNameMap.get(note.getChapterId()));
        vo.setSectionName(cataNameMap.get(note.getSectionId()));
        // 采集者名单
        List<Long> gatherIds = baseMapper.queryNoteGathers(id);
        if (gatherIds != null && !gatherIds.isEmpty()) {
            Map<Long, UserDTO> gatherMap = queryUserMap(new HashSet<>(gatherIds));
            vo.setGathers(gatherIds.stream()
                    .map(gatherMap::get)
                    .filter(Objects::nonNull)
                    .map(UserDTO::getName)
                    .toList());
        } else {
            vo.setGathers(List.of());
        }
        return vo;
    }

    @Override
    public void hiddenNote(Long id, boolean hidden) {
        updateById(new Note().setId(id).setHidden(hidden));
    }

    /**
     * 组装用户端 VO：批量补作者信息 + 批量查点赞状态。
     */
    private List<NoteVO> toVOList(List<Note> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        Set<Long> authorIds = records.stream()
                .map(Note::getAuthorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, UserDTO> authorMap = queryUserMap(authorIds);
        // 点赞状态按人查，只能实时问 fm-remark（bizType = NOTE）
        List<Long> noteIds = records.stream().map(Note::getId).toList();
        Set<Long> likedIds = remarkClient.isBizLiked(MqConstants.BizType.NOTE, noteIds);
        if (likedIds == null) {
            likedIds = Set.of();
        }

        List<NoteVO> list = new ArrayList<>(records.size());
        for (Note n : records) {
            NoteVO vo = BeanUtil.copyProperties(n, NoteVO.class);
            UserDTO author = authorMap.get(n.getAuthorId());
            if (author != null) {
                vo.setAuthorName(author.getName());
                vo.setAuthorIcon(author.getIcon());
            }
            vo.setLiked(likedIds.contains(n.getId()));
            vo.setGatheredByMe(Boolean.TRUE.equals(n.getGatheredByMe()));
            list.add(vo);
        }
        return list;
    }

    private Map<Long, UserDTO> queryUserMap(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return new HashMap<>();
        }
        List<UserDTO> users = userClient.queryUserByIds(new ArrayList<>(userIds));
        if (users == null || users.isEmpty()) {
            return new HashMap<>();
        }
        return users.stream().collect(Collectors.toMap(UserDTO::getId, u -> u, (a, b) -> a));
    }

    private Map<Long, String> queryCourseNames(Set<Long> courseIds) {
        if (courseIds == null || courseIds.isEmpty()) {
            return Map.of();
        }
        List<CourseSimpleDTO> courses = courseClient.queryCourseSimpleList(new ArrayList<>(courseIds));
        if (courses == null || courses.isEmpty()) {
            return Map.of();
        }
        return courses.stream()
                .collect(Collectors.toMap(CourseSimpleDTO::getId, CourseSimpleDTO::getName, (a, b) -> a));
    }

    private Map<Long, String> queryCataNames(Set<Long> cataIds) {
        cataIds = cataIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (cataIds.isEmpty()) {
            return Map.of();
        }
        List<CataSimpleDTO> catas = courseClient.queryCataSimpleList(new ArrayList<>(cataIds));
        if (catas == null || catas.isEmpty()) {
            return Map.of();
        }
        return catas.stream()
                .collect(Collectors.toMap(CataSimpleDTO::getId, CataSimpleDTO::getName, (a, b) -> a));
    }
}
