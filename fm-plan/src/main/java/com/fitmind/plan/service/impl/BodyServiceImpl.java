package com.fitmind.plan.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fitmind.api.client.user.UserClient;
import com.fitmind.api.dto.user.UserBodyProfileDTO;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.plan.constants.DietConstants;
import com.fitmind.plan.constants.DietErrorInfo;
import com.fitmind.plan.domain.dto.BodyMetricDTO;
import com.fitmind.plan.domain.po.UserBodyMetric;
import com.fitmind.plan.domain.vo.BodyMetricVO;
import com.fitmind.plan.mapper.UserBodyMetricMapper;
import com.fitmind.plan.service.IBodyService;
import com.fitmind.plan.utils.LoginUser;
import com.fitmind.plan.utils.NutritionCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 体征实现。
 *
 * <p><b>本类最容易写错的三处</b>：</p>
 * <ol>
 *   <li><b>目标（增肌/减脂）没有存在表里</b>：{@code user_body_metric} 没有 goal 列。
 *       这里按"目标体重 &lt; 当前体重 → 减脂，否则增肌"推断。
 *       为什么不加列：P0 的 {@code plan.goal} 是人工选的，
 *       而这里用户的真实意图就是"我想从 65 掉到 60"，从数据能推出来，不需要多一个状态；
 *       推不出来（没填目标）时才按增肌算。</li>
 *   <li><b>身高只在第一次问</b>：第二次起沿用上一次的值。
 *       每次都要求填身高的后果是用户每次记体重都要多填一格，然后他就会放弃记体重。</li>
 *   <li><b>没有体征时用 fm-user 的档案兜底</b>：{@code UserClient.queryBodyProfile} 返回
 *       height/weight/targetWeight。不兜底的话，新用户首页的"还可以吃"是 0/0，
 *       还得先被引导去填一遍他注册时已经填过的东西。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BodyServiceImpl implements IBodyService {

    private final UserBodyMetricMapper metricMapper;
    private final UserClient userClient;

    @Override
    public BodyMetricVO record(BodyMetricDTO dto) {
        Long userId = LoginUser.id();
        LocalDate date = dto.getDate() == null ? LocalDate.now() : dto.getDate();

        UserBodyMetric last = latestRow(userId);

        // 身高：本次传了就用本次的；否则沿用上一次；再没有就问档案；都没有 → 报错让他填
        BigDecimal height = dto.getHeightCm() != null
                ? dto.getHeightCm()
                : last != null ? last.getHeightCm() : profileHeight(userId);
        if (height == null) {
            throw new BizIllegalException(400, DietErrorInfo.Msg.HEIGHT_REQUIRED);
        }

        BigDecimal weight = dto.getWeightKg();
        BigDecimal target = dto.getTargetWeightKg() != null
                ? dto.getTargetWeightKg()
                : last != null ? last.getTargetWeightKg() : null;

        UserBodyMetric metric = buildMetric(userId, date, height, weight, target,
                dto.getTargetDate() != null ? dto.getTargetDate() : last != null ? last.getTargetDate() : null);
        metricMapper.upsert(metric);

        log.info("记录体征：userId={}, date={}, weightKg={}, kcalBudget={}",
                userId, date, weight, metric.getKcalBudget());
        return toVo(metric, false);
    }

    @Override
    public BodyMetricVO latest() {
        Long userId = LoginUser.id();
        UserBodyMetric row = latestRow(userId);
        if (row != null) {
            return toVo(row, false);
        }
        // 库里没有 → 用档案兜底展示（GET 不写库，避免"看一眼前提下改了数据"）
        UserBodyProfileDTO profile = safeProfile(userId);
        if (profile == null || profile.getWeight() == null) {
            BodyMetricVO empty = new BodyMetricVO();
            empty.setNeedProfile(true);
            empty.setFromProfile(false);
            return empty;
        }
        UserBodyMetric transientMetric = buildMetric(userId, LocalDate.now(), profile.getHeight(),
                profile.getWeight(), profile.getTargetWeight(), null);
        return toVo(transientMetric, true);
    }

    @Override
    public UserBodyMetric ensureMetric(Long userId) {
        UserBodyMetric row = latestRow(userId);
        if (row != null) {
            return row;
        }
        UserBodyProfileDTO profile = safeProfile(userId);
        if (profile == null || profile.getWeight() == null) {
            return null;
        }
        // 档案里有体重 → 落一行，之后每次读都走本地表（不再每次请求都打 fm-user）
        UserBodyMetric metric = buildMetric(userId, LocalDate.now(), profile.getHeight(),
                profile.getWeight(), profile.getTargetWeight(), null);
        metricMapper.upsert(metric);
        log.info("用用户档案初始化体征：userId={}, weightKg={}, kcalBudget={}",
                userId, metric.getWeightKg(), metric.getKcalBudget());
        return latestRow(userId);
    }

    // ==================== 内部 ====================

    /** 最新一行（按 record_date 倒序） */
    private UserBodyMetric latestRow(Long userId) {
        return metricMapper.selectOne(Wrappers.<UserBodyMetric>lambdaQuery()
                .eq(UserBodyMetric::getUserId, userId)
                .orderByDesc(UserBodyMetric::getRecordDate)
                .orderByDesc(UserBodyMetric::getId)
                .last("limit 1"));
    }

    /**
     * 目标推断：目标体重 < 当前体重 → 减脂，否则增肌。
     * ★ 这个判断只影响"蛋白系数（1.6 vs 2.0）与起算热量（28 vs 33）"，
     * 不会改动任何历史数据 —— 改目标只是下一行记录的事。
     */
    private String inferGoal(BigDecimal weight, BigDecimal target) {
        if (weight != null && target != null && target.compareTo(weight) < 0) {
            return DietConstants.GOAL_LOSS;
        }
        return DietConstants.GOAL_GAIN;
    }

    private UserBodyMetric buildMetric(Long userId, LocalDate date, BigDecimal height,
                                       BigDecimal weight, BigDecimal target, LocalDate targetDate) {
        String goal = inferGoal(weight, target);
        NutritionCalculator.MacroPlan plan = NutritionCalculator.plan(weight, goal);
        return new UserBodyMetric()
                .setUserId(userId)
                .setRecordDate(date)
                .setHeightCm(height)
                .setWeightKg(weight)
                .setTargetWeightKg(target)
                .setTargetDate(targetDate)
                .setBmi(NutritionCalculator.bmi(weight, height))
                .setKcalBudget(plan.kcalBudget())
                .setProteinG(plan.proteinG())
                .setCarbG(plan.carbG())
                .setFatG(plan.fatG());
    }

    /**
     * 查 fm-user 的档案。
     *
     * <p>★ 必须 try/catch：fm-user 没起、超时、或者这个用户根本没填档案，
     * 都不该让"记体重"整个失败 —— 那会把一个"锦上添花"的兜底变成一个阻塞点。</p>
     */
    private UserBodyProfileDTO safeProfile(Long userId) {
        try {
            return userClient.queryBodyProfile(userId);
        } catch (Exception e) {
            log.warn("查询用户档案失败（按没有档案处理）：userId={}, 原因={}", userId, e.getMessage());
            return null;
        }
    }

    private BigDecimal profileHeight(Long userId) {
        UserBodyProfileDTO profile = safeProfile(userId);
        return profile == null ? null : profile.getHeight();
    }

    private BodyMetricVO toVo(UserBodyMetric m, boolean fromProfile) {
        BodyMetricVO vo = new BodyMetricVO();
        vo.setRecordDate(fromProfile ? null : m.getRecordDate());
        vo.setHeightCm(m.getHeightCm());
        vo.setWeightKg(m.getWeightKg());
        vo.setTargetWeightKg(m.getTargetWeightKg());
        vo.setTargetDate(m.getTargetDate());
        vo.setBmi(m.getBmi());
        vo.setKcalBudget(m.getKcalBudget());
        vo.setProteinG(m.getProteinG());
        vo.setCarbG(m.getCarbG());
        vo.setFatG(m.getFatG());
        vo.setNeedProfile(false);
        vo.setFromProfile(fromProfile);
        return vo;
    }
}
