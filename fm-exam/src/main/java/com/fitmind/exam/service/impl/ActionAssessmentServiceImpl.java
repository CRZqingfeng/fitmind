package com.fitmind.exam.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.api.client.media.MediaClient;
import com.fitmind.api.client.message.MessageClient;
import com.fitmind.api.constants.NoticeTemplateCode;
import com.fitmind.api.client.user.UserClient;
import com.fitmind.api.dto.message.NoticeSendDTO;
import com.fitmind.api.dto.user.UserDTO;
import com.fitmind.api.dto.user.UserDTO;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.domain.query.PageQuery;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.utils.UserContext;
import com.fitmind.exam.constants.ExamErrorInfo;
import com.fitmind.exam.domain.dto.ActionReviewDTO;
import com.fitmind.exam.domain.dto.ActionSubmitDTO;
import com.fitmind.exam.domain.po.ActionAssessment;
import com.fitmind.exam.domain.vo.ActionAssessmentVO;
import com.fitmind.exam.mapper.ActionAssessmentMapper;
import com.fitmind.exam.service.IActionAssessmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 动作考核服务实现。
 *
 * <p><b>★ 评分是人工的</b>（教练看动作标不标准），
 * 不是规则自动判分 —— 动作质量本质是视觉判断，规则写不出来；
 * 而"知识类"的自动判分 M2-2c 已经做过（课程侧 subject + 服务端判分）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActionAssessmentServiceImpl extends ServiceImpl<ActionAssessmentMapper, ActionAssessment>
        implements IActionAssessmentService {

    private final MediaClient mediaClient;
    private final UserClient userClient;
    private final MessageClient messageClient;

    /** 待评分 */
    private static final int STATUS_PENDING = 1;
    /** 已评分 */
    private static final int STATUS_REVIEWED = 2;
    /** fm_user 的用户类型：1-管理员 2-学员 3-教练 */
    private static final int USER_TYPE_STUDENT = 2;

    @Override
    public Long submit(ActionSubmitDTO dto) {
        Long userId = UserContext.getUser();
        // ★ 校验媒资真实存在（防止传一个不存在的 mediaId 进来）
        if (mediaClient.queryMediaById(dto.getMediaId()) == null) {
            throw new BizIllegalException(ExamErrorInfo.Msg.ACTION_VIDEO_REQUIRED);
        }
        ActionAssessment record = new ActionAssessment()
                .setUserId(userId)
                .setCourseId(dto.getCourseId())
                .setCataId(dto.getCataId())
                .setMediaId(dto.getMediaId())
                .setRemark(dto.getRemark())
                .setStatus(STATUS_PENDING)
                .setSubmitTime(LocalDateTime.now());
        save(record);
        log.info("提交动作考核：userId={}, 记录id={}, 媒资id={}", userId, record.getId(), dto.getMediaId());
        return record.getId();
    }

    @Override
    public void review(ActionReviewDTO dto) {
        ActionAssessment record = getById(dto.getId());
        if (record == null) {
            throw new BizIllegalException(ExamErrorInfo.Msg.ACTION_NOT_EXISTS);
        }
        if (record.reviewed()) {
            throw new BizIllegalException(ExamErrorInfo.Msg.ACTION_REVIEWED);
        }
        Long coachId = UserContext.getUser();
        // ★ 角色校验：评分这件事**教练和管理员都能做**，但学员不行。
        //   这里不能用"只允许管理员"的拦截器（那是 fm-learning 的口径）——
        //   给学员的动作打分本来就是教练的职责。
        Integer userType = userClient.queryUserType(coachId);
        if (userType == null || userType == USER_TYPE_STUDENT) {
            throw new BizIllegalException(ExamErrorInfo.Msg.NOT_COACH);
        }
        // ★★ 条件更新：只有"待评分 → 已评分"成功才算数。
        //    挡的是"两个教练同时打分"或"重复提交评分请求"
        int n = baseMapper.review(record.getId(), STATUS_PENDING, STATUS_REVIEWED,
                dto.getScore(), dto.getComment(), coachId);
        if (n == 0) {
            throw new BizIllegalException(ExamErrorInfo.Msg.ACTION_REVIEWED);
        }
        log.info("动作考核评分完成：考核id={}, 学员={}, 教练={}, 分数={}",
                record.getId(), record.getUserId(), coachId, dto.getScore());

        // ★ 通知学员"结果出来了"。
        //
        // 这里**直接调 Feign 而不是发 MQ 事件**，理由是它和支付/退款那类通知性质不同：
        //   · 支付/退款通知是"业务操作的副作用"，交易主链路不能被通知拖慢 → 走 MQ
        //   · 评分通知本身就是这次操作的目的（教练打完分就是要告诉学员），
        //     而且低频、非高并发、失败也不影响主流程
        // 一句话：**业务副作用走 MQ，用户可感知的动作可以直接调**。
        //
        // 幂等：sourceId 传考核记录id，重复调用被 user_inbox 的唯一索引挡住。
        // 降级：MessageClientFallback 只记日志不抛异常 —— 通知发不出去
        //       不能让"教练评不了分"（结果已经落库了，学员去查也能看到）。
        notifyStudent(record, dto);
    }

    /** 评分完成后通知学员（失败不影响评分结果） */
    private void notifyStudent(ActionAssessment record, ActionReviewDTO dto) {
        try {
            messageClient.sendByTemplate(new NoticeSendDTO()
                    .setUserId(record.getUserId())
                    .setTemplateCode(NoticeTemplateCode.ACTION_REVIEWED)
                    // ⚠️ 不传动作名：它在 fm-course 的课程大纲里，
                    //    为了一条通知去查课程服务会把课程服务变成通知链路的下游依赖
                    //    （课程服务一慢，评分就慢），不划算
                    .setParams(Map.of(
                            "score", dto.getScore() == null ? 0 : dto.getScore(),
                            "comment", dto.getComment() == null || dto.getComment().isBlank()
                                    ? "无" : dto.getComment()))
                    .setSourceId(record.getId()));
            log.info("考核结果通知已发出：assessmentId={}, userId={}", record.getId(), record.getUserId());
        } catch (Exception e) {
            // 双保险：fallback 已经吞了大部分情况，这里兜住其它意外（如参数拼错）
            log.error("考核结果通知发送失败（不影响评分）：assessmentId={}", record.getId(), e);
        }
    }

    @Override
    public ActionAssessmentVO detail(Long id) {
        ActionAssessment record = getById(id);
        if (record == null) {
            throw new BizIllegalException(ExamErrorInfo.Msg.ACTION_NOT_EXISTS);
        }
        if (!Objects.equals(record.getUserId(), UserContext.getUser())) {
            throw new BizIllegalException(ExamErrorInfo.Msg.ACTION_NOT_MINE);
        }
        return toVO(record);
    }

    @Override
    public PageDTO<ActionAssessmentVO> mySubmissions(PageQuery query) {
        Page<ActionAssessment> page = lambdaQuery()
                .eq(ActionAssessment::getUserId, UserContext.getUser())
                .orderByDesc(ActionAssessment::getSubmitTime)
                .page(query.toMpPage());
        return PageDTO.of(page, this::toVO);
    }

    @Override
    public PageDTO<ActionAssessmentVO> pendingList(PageQuery query) {
        Page<ActionAssessment> page = lambdaQuery()
                .eq(ActionAssessment::getStatus, STATUS_PENDING)
                .orderByAsc(ActionAssessment::getSubmitTime)
                .page(query.toMpPage());
        PageDTO<ActionAssessmentVO> dto = PageDTO.of(page, this::toVO);
        // ★ 管理端列表要显示"是谁提交的" —— 批量补一次用户名
        fillUserNames(dto.getList());
        return dto;
    }

    /**
     * 批量补学员名（管理端列表用）。
     *
     * <p>★ 用 {@code queryUserByIds} **一次查一批**，而不是循环单查：
     * 待评分列表一页 10~20 条，N+1 次 Feign 会把这个接口的耗时直接乘以条数。</p>
     *
     * <p>★ 失败**不影响列表**：名字只是展示信息，拉不到就先空着（前端显示 "—"）。
     * 不能因为 fm-user 抖了一下，就让教练连待评分列表都打不开。</p>
     */
    private void fillUserNames(List<ActionAssessmentVO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        List<Long> ids = list.stream()
                .map(ActionAssessmentVO::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        try {
            List<UserDTO> users = userClient.queryUserByIds(ids);
            Map<Long, String> nameById = users.stream()
                    .filter(u -> u.getId() != null)
                    .collect(Collectors.toMap(UserDTO::getId,
                            u -> u.getName() == null ? "" : u.getName(), (a, b) -> a));
            list.forEach(v -> v.setUserName(nameById.get(v.getUserId())));
        } catch (Exception e) {
            log.warn("补学员名失败（不影响待评分列表）：{}", e.getMessage());
        }
    }

    private ActionAssessmentVO toVO(ActionAssessment r) {
        return new ActionAssessmentVO()
                .setId(r.getId())
                .setUserId(r.getUserId())
                .setCourseId(r.getCourseId())
                .setCataId(r.getCataId())
                // ★★ M7-10 补：提交时只存了 mediaId；播放地址是带签名的临时地址、会过期，
                //    不该存库 —— 前端拿 mediaId 现取一次 play-url 才拿得到有效地址。
                //    （原先这里只透传 videoUrl，而它从来没被赋过值 → 学员和教练都看不到视频。）
                .setMediaId(r.getMediaId())
                .setVideoUrl(r.getVideoUrl())
                .setRemark(r.getRemark())
                .setStatus(r.getStatus())
                .setStatusDesc(r.getStatus() != null && r.getStatus() == STATUS_REVIEWED ? "已评分" : "待评分")
                .setScore(r.getScore())
                .setComment(r.getComment())
                .setCoachId(r.getCoachId())
                .setSubmitTime(r.getSubmitTime())
                .setReviewTime(r.getReviewTime());
    }
}
