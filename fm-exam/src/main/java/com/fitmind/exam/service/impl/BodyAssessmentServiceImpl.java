package com.fitmind.exam.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.api.client.user.UserClient;
import com.fitmind.api.dto.user.UserBodyProfileDTO;
import com.fitmind.api.dto.user.UserDTO;
import com.fitmind.common.constants.MqConstants;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.domain.query.PageQuery;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.mq.message.AssessmentCreatedMessage;
import com.fitmind.common.utils.RabbitMqHelper;
import com.fitmind.common.utils.UserContext;
import com.fitmind.exam.constants.BodyLevel;
import com.fitmind.exam.constants.ExamErrorInfo;
import com.fitmind.exam.constants.Gender;
import com.fitmind.exam.domain.dto.BodyAssessFormDTO;
import com.fitmind.exam.domain.po.BodyAssessment;
import com.fitmind.exam.domain.vo.BodyAssessVO;
import com.fitmind.exam.domain.vo.BodyTrendVO;
import com.fitmind.exam.mapper.BodyAssessmentMapper;
import com.fitmind.exam.service.IBodyAssessmentService;
import com.fitmind.exam.util.BodyMetricsUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 体测评估服务实现。
 *
 * <p><b>★ 本类最值得看的是 {@link #assess} 里"先算后存"的顺序</b>：
 * 所有指标先在内存里算完（纯函数），一次写入数据库。
 * 没有"先存后算"，也没有"算一半失败"的中间态 ——
 * 体测记录是<b>凭证</b>，要么完整要么不存在。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BodyAssessmentServiceImpl extends ServiceImpl<BodyAssessmentMapper, BodyAssessment>
        implements IBodyAssessmentService {

    private final UserClient userClient;
    private final RabbitMqHelper rabbitMqHelper;

    /** 性别未知时是否允许用"中性标准"评估（见 yml 注释） */
    @Value("${exam.allow-unknown-gender:true}")
    private boolean allowUnknownGender;

    // ==================== 提交体测 ====================

    @Override
    public Long assess(BodyAssessFormDTO dto) {
        Long userId = UserContext.getUser();
        // 1. 取性别与年龄（★ 三项评估标准都依赖性别；BMR 需要年龄）
        Gender gender = queryGender(userId);
        if (!gender.known() && !allowUnknownGender) {
            throw new BizIllegalException("请先完善档案中的性别信息");
        }
        Integer age = queryAge(userId);

        // 2. 算指标（纯函数，算错了会抛 IllegalArgumentException）
        BigDecimal bmi, whr = null, fatMass = null, leanMass = null;
        Integer bmiLevel, fatLevel = null, whrLevel = null, bmr = null, tdee = null;
        try {
            bmi = BodyMetricsUtil.bmi(dto.getHeight(), dto.getWeight());
            bmiLevel = BodyMetricsUtil.bmiLevel(bmi);
            if (dto.getBodyFatRate() != null) {
                fatLevel = BodyMetricsUtil.fatLevel(gender, dto.getBodyFatRate());
                fatMass = BodyMetricsUtil.fatMass(dto.getWeight(), dto.getBodyFatRate());
                leanMass = BodyMetricsUtil.leanMass(dto.getWeight(), fatMass);
            }
            // ★ 腰围/臀围必须成对：只填一个本来也算不出腰臀比，
            //   但**不能静默跳过** —— 用户以为 WHR 评估过了，
            //   实际上结论里少了一项，而他会照着那个结论去调整训练。
            //   （M2-2c 踩过同类坑：错误提示写「选项需要 2~10 个」而代码只挡了 >10，
            //     文案与实现不一致，编译器不会提醒你。）
            boolean hasWaist = dto.getWaist() != null;
            boolean hasHip = dto.getHip() != null && dto.getHip().doubleValue() > 0;
            if (hasWaist != hasHip) {
                throw new BizIllegalException(
                        "腰围和臀围要一起填（腰臀比 = 腰围 ÷ 臀围），只填一个算不出来");
            }
            if (hasWaist) {
                whr = BodyMetricsUtil.whr(dto.getWaist(), dto.getHip());
                whrLevel = BodyMetricsUtil.whrLevel(gender, whr);
            }
            bmr = BodyMetricsUtil.bmr(gender, dto.getWeight(), dto.getHeight(), age);
            tdee = BodyMetricsUtil.tdee(bmr, queryWeeklyFrequency(userId));
        } catch (IllegalArgumentException e) {
            // 参数不合法（比如臀围填了 0）：转成业务异常，不要让 500 冒出去
            throw new BizIllegalException(String.format(ExamErrorInfo.Msg.BODY_PARAM_ILLEGAL, e.getMessage()));
        }
        int score = BodyMetricsUtil.score(bmiLevel, fatLevel, whrLevel);

        // 3. 生成结论与建议
        LocalDateTime assessTime = dto.getAssessTime() == null ? LocalDateTime.now() : dto.getAssessTime();
        BodyAssessment record = new BodyAssessment()
                .setUserId(userId)
                .setGender(gender.getValue())
                .setHeight(dto.getHeight())
                .setWeight(dto.getWeight())
                .setTargetWeight(dto.getTargetWeight())
                .setBodyFatRate(dto.getBodyFatRate())
                .setWaist(dto.getWaist())
                .setChest(dto.getChest())
                .setArm(dto.getArm())
                .setHip(dto.getHip())
                .setBmi(bmi).setBmiLevel(bmiLevel)
                .setFatLevel(fatLevel)
                .setWhr(whr).setWhrLevel(whrLevel)
                .setBmr(bmr).setTdee(tdee)
                .setFatMass(fatMass).setLeanMass(leanMass)
                .setScore(score)
                .setGoalType(dto.getGoalType())
                .setSummary(buildSummary(bmiLevel, fatLevel, whrLevel, score))
                .setSuggestion(buildSuggestion(gender, dto.getGoalType(), bmiLevel, fatLevel, whrLevel, tdee))
                .setAssessTime(assessTime);
        save(record);

        // 4. 发事件（数据中心统计"今日新增体测"）
        rabbitMqHelper.sendAfterCommit(MqConstants.Exchange.EXAM_EXCHANGE,
                MqConstants.Key.ASSESSMENT_CREATED_KEY,
                AssessmentCreatedMessage.of(record.getId(), userId, score, assessTime));

        log.info("体测完成：userId={}, 记录id={}, BMI={}({}), 评分={}",
                userId, record.getId(), bmi, BodyLevel.descOf(1, bmiLevel), score);
        return record.getId();
    }

    // ==================== 查询 ====================

    @Override
    public BodyAssessVO latest() {
        List<BodyAssessment> list = lambdaQuery()
                .eq(BodyAssessment::getUserId, UserContext.getUser())
                .orderByDesc(BodyAssessment::getAssessTime)
                .last("LIMIT 1")
                .list();
        return list.isEmpty() ? null : toVO(list.get(0));
    }

    @Override
    public BodyAssessVO detail(Long id) {
        BodyAssessment record = getById(id);
        if (record == null) {
            throw new BizIllegalException(ExamErrorInfo.Msg.BODY_NOT_EXISTS);
        }
        // ★ 只能看自己的
        if (!java.util.Objects.equals(record.getUserId(), UserContext.getUser())) {
            throw new BizIllegalException(ExamErrorInfo.Msg.BODY_NOT_MINE);
        }
        return toVO(record);
    }

    @Override
    public PageDTO<BodyAssessVO> myHistory(PageQuery query) {
        Page<BodyAssessment> page = lambdaQuery()
                .eq(BodyAssessment::getUserId, UserContext.getUser())
                .orderByDesc(BodyAssessment::getAssessTime)
                .page(query.toMpPage());
        return PageDTO.of(page, this::toVO);
    }

    /**
     * ★ 趋势：首次 vs 最新。
     *
     * <p>为什么重点给"体重 / 体脂 / 腰围 / 评分"四个差值：
     * 体重降了是好事，但<b>如果去脂体重也跟着降，减掉的其实是肌肉</b> ——
     * 单看体重会得出"练得不错"的错误结论。
     * 所以体重和体脂必须一起看。</p>
     */
    @Override
    public BodyTrendVO trend() {
        List<BodyAssessment> list = lambdaQuery()
                .eq(BodyAssessment::getUserId, UserContext.getUser())
                .orderByAsc(BodyAssessment::getAssessTime)
                .list();
        if (list.isEmpty()) {
            return null;
        }
        BodyAssessment first = list.get(0);
        BodyAssessment latest = list.get(list.size() - 1);
        BodyTrendVO vo = new BodyTrendVO()
                .setFirst(toVO(first))
                .setLatest(toVO(latest))
                .setTimes(list.size())
                .setDays(ChronoUnit.DAYS.between(first.getAssessTime(), latest.getAssessTime()));
        if (list.size() >= 2) {
            vo.setWeightChange(diff(latest.getWeight(), first.getWeight()))
                    .setFatRateChange(diff(latest.getBodyFatRate(), first.getBodyFatRate()))
                    .setWaistChange(diff(latest.getWaist(), first.getWaist()))
                    .setScoreChange(latest.getScore() == null || first.getScore() == null
                            ? null : latest.getScore() - first.getScore());
        }
        return vo;
    }

    // ==================== 结论与建议 ====================

    /**
     * 一句话结论。
     *
     * <p>只把"不正常的项"点出来 —— 全都正常时说一句鼓励的话。
     * 结论里不重复罗列所有数字（那些在 VO 的单独字段里），
     * 避免用户看到一大段文字抓不住重点。</p>
     */
    private String buildSummary(Integer bmiLevel, Integer fatLevel, Integer whrLevel, int score) {
        StringBuilder sb = new StringBuilder("综合评分 ").append(score).append(" 分；");
        boolean allNormal = (bmiLevel == null || bmiLevel == 2)
                && (fatLevel == null || fatLevel == 2 || fatLevel == 3)
                && (whrLevel == null || whrLevel == 1);
        if (allNormal) {
            sb.append("各项指标均在正常范围，保持当前的训练节奏就好。");
            return sb.toString();
        }
        if (bmiLevel != null && bmiLevel != 2) {
            sb.append("BMI ").append(BodyLevel.descOf(1, bmiLevel)).append("；");
        }
        if (fatLevel != null && fatLevel != 2 && fatLevel != 3) {
            sb.append("体脂率").append(BodyLevel.descOf(2, fatLevel)).append("；");
        }
        if (whrLevel != null && whrLevel == 2) {
            sb.append("腰臀比提示腹部脂肪偏多，建议关注心血管风险。");
        }
        return sb.toString();
    }

    /**
     * ★ 训练建议（本模块对用户最有价值的部分）。
     *
     * <p>三段式：<b>热量目标 → 训练侧重 → 风险提示</b>，最后补一句性别未知的声明。
     * 只给"指标的正常/不正常"是不够的 —— 用户真正要的是"那我该怎么做"。</p>
     *
     * <p>热量缺口取 500 kcal/天（≈ 每周减 0.5kg，这是公认的安全速度），
     * 增肌盈余取 300 kcal/天（再多就主要长脂肪了）。</p>
     */
    private String buildSuggestion(Gender gender, Integer goalType, Integer bmiLevel,
                                   Integer fatLevel, Integer whrLevel, Integer tdee) {
        StringBuilder sb = new StringBuilder();
        // ① 热量目标
        if (tdee != null) {
            if (goalType != null && goalType == 2) {
                sb.append("每日热量摄入建议 ").append(tdee - 500)
                        .append(" kcal（在总消耗基础上留 500 kcal 缺口，约每周减重 0.5kg）。");
            } else if (goalType != null && goalType == 1) {
                sb.append("每日热量摄入建议 ").append(tdee + 300)
                        .append(" kcal（盈余 300 kcal 配合力量训练增肌）。");
            } else {
                sb.append("每日热量摄入建议维持在 ").append(tdee).append(" kcal 左右，保持体重。");
            }
        }
        // ② 训练侧重
        if (bmiLevel != null && (bmiLevel == 3 || bmiLevel == 4)) {
            sb.append("建议以有氧训练为主，配合饮食控制，先把体重降到合理区间。");
        } else if (goalType != null && goalType == 1) {
            sb.append("建议以力量训练为主，每周 3-5 次，注重大肌群复合动作。");
        } else if (goalType != null && goalType == 3) {
            sb.append("建议力量与有氧结合，每周 3-4 次，兼顾线条与代谢。");
        }
        // ③ 风险提示
        if (whrLevel != null && whrLevel == 2) {
            sb.append("⚠️ 腰臀比偏高提示腹部脂肪堆积，心血管风险上升，建议增加有氧训练并控制精制碳水。");
        }
        if (fatLevel != null && fatLevel == 5) {
            sb.append("体脂率处于肥胖区间，建议把减脂作为现阶段的唯一目标。");
        }
        // ④ 性别未知的声明（★ 不能静默按某一性别算）
        if (!gender.known()) {
            sb.append("（档案中未设置性别，体脂与腰臀比按中性标准评估，补全档案可获得更准确的结论）");
        }
        return sb.toString();
    }

    // ==================== 辅助 ====================

    private Gender queryGender(Long userId) {
        UserDTO user = queryUser(userId);
        return user == null ? Gender.UNKNOWN : Gender.of(user.getGender());
    }

    private Integer queryAge(Long userId) {
        UserDTO user = queryUser(userId);
        return user == null ? null : BodyMetricsUtil.age(user.getBirthday());
    }

    /**
     * 每周训练次数（TDEE 的活动系数要用）。
     *
     * <p>它存在 {@code fm_user.user_body_profile} 里，而 UserClient 没暴露这个字段 ——
     * 这里取不到就传 null，{@code tdee()} 会按"久坐 1.2"算，
     * 并在建议里体现为偏低的热量目标。<b>不会因为取不到就放弃整次评估。</b></p>
     */
    private Integer queryWeeklyFrequency(Long userId) {
        try {
            UserBodyProfileDTO profile = userClient.queryBodyProfile(userId);
            return profile == null ? null : profile.getWeeklyFrequency();
        } catch (Exception e) {
            // 档案服务不可用：退回"久坐"系数（TDEE 偏低但保守），
            // 而不是让整个体测失败 —— 体测本身的数据仍然是有效的
            log.warn("查询身体档案失败，TDEE 按默认活动系数计算：userId={}", userId, e);
            return null;
        }
    }

    /** 查用户（用免登录内部接口，避免在异步/无上下文线程里 401） */
    private UserDTO queryUser(Long userId) {
        try {
            return userClient.queryUserInternal(userId);
        } catch (Exception e) {
            // 用户服务不可用：降级为"性别未知 + 年龄未知"，体测照常记录
            log.warn("查询用户信息失败，本次体测按未知性别评估：userId={}", userId, e);
            return null;
        }
    }

    /** 差值计算（任一为空则返回 null，不硬凑 0） */
    private BigDecimal diff(BigDecimal latest, BigDecimal first) {
        if (latest == null || first == null) {
            return null;
        }
        return latest.subtract(first);
    }

    private BodyAssessVO toVO(BodyAssessment r) {
        return new BodyAssessVO()
                .setId(r.getId())
                .setGender(r.getGender())
                .setHeight(r.getHeight())
                .setWeight(r.getWeight())
                .setTargetWeight(r.getTargetWeight())
                .setBodyFatRate(r.getBodyFatRate())
                .setWaist(r.getWaist())
                .setChest(r.getChest())
                .setArm(r.getArm())
                .setHip(r.getHip())
                .setBmi(r.getBmi())
                .setBmiLevel(r.getBmiLevel())
                .setBmiLevelDesc(BodyLevel.descOf(1, r.getBmiLevel()))
                .setFatLevel(r.getFatLevel())
                .setFatLevelDesc(BodyLevel.descOf(2, r.getFatLevel()))
                .setWhr(r.getWhr())
                .setWhrLevel(r.getWhrLevel())
                .setWhrLevelDesc(BodyLevel.descOf(3, r.getWhrLevel()))
                .setBmr(r.getBmr())
                .setTdee(r.getTdee())
                .setLeanMass(r.getLeanMass())
                .setFatMass(r.getFatMass())
                .setScore(r.getScore())
                .setGoalType(r.getGoalType())
                .setSummary(r.getSummary())
                .setSuggestion(r.getSuggestion())
                .setAssessTime(r.getAssessTime());
    }
}
