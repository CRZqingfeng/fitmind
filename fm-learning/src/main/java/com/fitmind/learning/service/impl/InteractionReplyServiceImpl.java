package com.fitmind.learning.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.api.client.remark.RemarkClient;
import com.fitmind.api.client.user.UserClient;
import com.fitmind.api.dto.user.UserDTO;
import com.fitmind.common.constants.MqConstants;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.exceptions.BadRequestException;
import com.fitmind.common.utils.RabbitMqHelper;
import com.fitmind.common.utils.UserContext;
import com.fitmind.learning.domain.dto.ReplyDTO;
import com.fitmind.learning.domain.po.InteractionQuestion;
import com.fitmind.learning.domain.po.InteractionReply;
import com.fitmind.learning.domain.query.ReplyPageQuery;
import com.fitmind.learning.domain.vo.ReplyVO;
import com.fitmind.learning.enums.QuestionStatus;
import com.fitmind.learning.mapper.InteractionReplyMapper;
import com.fitmind.common.mq.message.PointsEventMessage;
import com.fitmind.learning.service.IInteractionQuestionService;
import com.fitmind.learning.service.IInteractionReplyService;
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
 * 互动回答服务实现。
 *
 * <p>回答与评论共用一张表：{@code answerId = 0} 表示顶层回答，否则表示"评论某条回答"。
 * 这就是楼中楼的做法 —— 不需要递归查询，一次分页就能出一层。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InteractionReplyServiceImpl extends ServiceImpl<InteractionReplyMapper, InteractionReply>
        implements IInteractionReplyService {

    /** 顶层回答的哨兵值（不是 NULL，见 InteractionReply 的注释） */
    private static final long TOP_LEVEL = 0L;

    private final IInteractionQuestionService questionService;

    private final UserClient userClient;

    private final RemarkClient remarkClient;

    private final RabbitMqHelper rabbitMqHelper;

    @Override
    @Transactional
    public void saveReply(ReplyDTO dto) {
        Long userId = UserContext.getUser();
        long answerId = dto.getAnswerId() == null ? TOP_LEVEL : dto.getAnswerId();
        boolean isAnswer = answerId == TOP_LEVEL;

        // ★ 「是不是学员」由服务端判定，不看前端传参 ——
        //   常见实现的 isStudent 是前端传的，能被伪造（虽然伪造的动机不大，但做法不对）
        Integer userType = userClient.queryUserType(userId);
        boolean isStudent = userType != null && userType == 2;

        InteractionReply reply = new InteractionReply()
                .setQuestionId(dto.getQuestionId())
                .setAnswerId(answerId)
                .setUserId(userId)
                .setContent(dto.getContent())
                .setTargetReplyId(dto.getTargetReplyId())
                .setTargetUserId(dto.getTargetUserId())
                .setAnonymity(Boolean.TRUE.equals(dto.getAnonymity()))
                .setReplyTimes(0)
                .setLikedTimes(0)
                .setHidden(false);
        save(reply);

        // ★ 用 reply.getId()，不是 reply.getAnswerId()。
        //   常见实现这里写的是 reply.getAnswerId()，而它在"新增回答"时必然为 null
        //   （这正是 isAnswer 的判定依据），结果「最新回答」永远被写成空。
        questionService.lambdaUpdate()
                .set(isAnswer, InteractionQuestion::getLatestAnswerId, reply.getId())
                .setSql(isAnswer, "answer_times = answer_times + 1")
                // 学员补了新回答 → 问题回到「未查看」，提醒管理端再看一眼
                .set(isStudent, InteractionQuestion::getStatus, QuestionStatus.UN_CHECK.getValue())
                .eq(InteractionQuestion::getId, dto.getQuestionId())
                .update();

        if (!isAnswer) {
            // 评论 → 上级回答的评论数原子累加（setSql 而不是先查再写，避免并发覆盖）
            lambdaUpdate()
                    .setSql("reply_times = reply_times + 1")
                    .eq(InteractionReply::getId, answerId)
                    .update();
        }

        // 学员回复加积分：走 MQ 异步（不拖慢主流程），且必须等事务提交后再发
        // —— 消息比提交先到的话，消费端拿不到刚写入的数据（M2-3 踩过的坑）
        if (isStudent) {
            rabbitMqHelper.sendAfterCommit(MqConstants.Exchange.LEARNING_EXCHANGE,
                    MqConstants.Key.WRITE_REPLY,
                    PointsEventMessage.of(userId, reply.getId().toString()));
        }
    }

    @Override
    public PageDTO<ReplyVO> queryReplyPage(ReplyPageQuery query, boolean forAdmin) {
        Long questionId = query.getQuestionId();
        Long answerId = query.getAnswerId();
        if (questionId == null && answerId == null) {
            throw new BadRequestException("问题id和回答id不能都为空");
        }
        // questionId 有值 = 查"问题下的回答"（answer_id 固定 0）；否则 = 查"某回答下的评论"
        boolean isQueryAnswer = questionId != null;
        Page<InteractionReply> page = lambdaQuery()
                .eq(isQueryAnswer, InteractionReply::getQuestionId, questionId)
                .eq(InteractionReply::getAnswerId, isQueryAnswer ? TOP_LEVEL : answerId)
                .eq(!forAdmin, InteractionReply::getHidden, false)
                // 点赞多的排前面，同分再按时间正序（跟常见实现一致）
                .page(query.toMpPage(OrderItem.desc("liked_times"), OrderItem.asc("create_time")));
        List<InteractionReply> records = page.getRecords();
        if (records == null || records.isEmpty()) {
            return PageDTO.empty(page);
        }
        return PageDTO.of(page, toVOList(records, forAdmin));
    }

    @Override
    public ReplyVO queryReplyById(Long id) {
        InteractionReply reply = getById(id);
        if (reply == null) {
            return null;
        }
        // 管理端详情：forAdmin = true，匿名也返回真实信息
        return toVOList(List.of(reply), true).get(0);
    }

    @Override
    @Transactional
    public void hiddenReply(Long id, Boolean hidden) {
        InteractionReply old = getById(id);
        if (old == null) {
            return;
        }
        updateById(new InteractionReply().setId(id).setHidden(Boolean.TRUE.equals(hidden)));
        // 顶层回答被隐藏时，它下面的评论要一起隐藏 —— 否则评论还挂在
        // 一条不存在的回答下面（用户看不到上下文，只看到一堆莫名其妙的评论）
        if (old.getAnswerId() == null || old.getAnswerId() == TOP_LEVEL) {
            lambdaUpdate()
                    .set(InteractionReply::getHidden, Boolean.TRUE.equals(hidden))
                    .eq(InteractionReply::getAnswerId, id)
                    .update();
        }
    }

    /**
     * 批量组装 VO：用户信息、楼中楼的回复目标、以及"我点过赞没"。
     *
     * <p>三次批量查询代替 3N 次单查：用户一次、目标回复一次、点赞状态一次。</p>
     */
    private List<ReplyVO> toVOList(List<InteractionReply> records, boolean forAdmin) {
        Set<Long> userIds = new HashSet<>();
        Set<Long> targetReplyIds = new HashSet<>();
        List<Long> replyIds = new ArrayList<>(records.size());
        for (InteractionReply r : records) {
            if (!Boolean.TRUE.equals(r.getAnonymity()) || forAdmin) {
                if (r.getUserId() != null) {
                    userIds.add(r.getUserId());
                }
            }
            if (r.getTargetReplyId() != null) {
                targetReplyIds.add(r.getTargetReplyId());
            }
            replyIds.add(r.getId());
        }
        // 目标回复的作者也要展示（"回复 @某某"）
        if (!targetReplyIds.isEmpty()) {
            for (InteractionReply t : listByIds(targetReplyIds)) {
                if ((!Boolean.TRUE.equals(t.getAnonymity()) || forAdmin) && t.getUserId() != null) {
                    userIds.add(t.getUserId());
                }
            }
        }
        Map<Long, UserDTO> userMap = queryUserMap(userIds);
        // ★ 点赞状态是"按人"的，没法像点赞数那样冗余到本表，只能实时问 fm-remark
        Set<Long> likedIds = remarkClient.isBizLiked(
                MqConstants.BizType.QA, new ArrayList<>(replyIds));
        if (likedIds == null) {
            likedIds = Set.of();
        }

        List<ReplyVO> list = new ArrayList<>(records.size());
        for (InteractionReply r : records) {
            ReplyVO vo = BeanUtil.copyProperties(r, ReplyVO.class);
            vo.setLiked(likedIds.contains(r.getId()));
            if (!Boolean.TRUE.equals(r.getAnonymity()) || forAdmin) {
                UserDTO user = userMap.get(r.getUserId());
                if (user != null) {
                    vo.setUserName(user.getName());
                    vo.setUserIcon(user.getIcon());
                    vo.setUserType(user.getType());
                }
            }
            if (r.getTargetReplyId() != null) {
                UserDTO target = userMap.get(r.getTargetUserId());
                if (target != null) {
                    vo.setTargetUserName(target.getName());
                }
            }
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
}
