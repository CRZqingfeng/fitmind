package com.fitmind.user.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.user.domain.dto.BodyProfileFormDTO;
import com.fitmind.user.domain.po.UserBodyProfile;
import com.fitmind.user.domain.vo.BodyProfileVO;
import com.fitmind.user.enums.GoalType;
import com.fitmind.user.enums.TrainingLevel;
import com.fitmind.user.mapper.UserBodyProfileMapper;
import com.fitmind.user.service.IBodyProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 身体档案服务实现 ★。
 */
@Slf4j
@Service
public class BodyProfileServiceImpl extends ServiceImpl<UserBodyProfileMapper, UserBodyProfile>
        implements IBodyProfileService {

    /** 身高换算成米时的除数 */
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Override
    public BodyProfileVO getByUserId(Long userId) {
        UserBodyProfile profile = lambdaQuery()
                .eq(UserBodyProfile::getUserId, userId)
                .one();
        if (profile == null) {
            return null;
        }
        return toVO(profile);
    }

    @Override
    public void saveOrUpdateProfile(Long userId, BodyProfileFormDTO dto) {
        UserBodyProfile profile = lambdaQuery()
                .eq(UserBodyProfile::getUserId, userId)
                .one();
        boolean isNew = profile == null;
        if (isNew) {
            profile = new UserBodyProfile();
            profile.setUserId(userId);
        }
        // 1.拷贝基础字段
        profile.setHeight(dto.getHeight())
                .setWeight(dto.getWeight())
                .setTargetWeight(dto.getTargetWeight())
                .setBodyFatRate(dto.getBodyFatRate())
                .setWaist(dto.getWaist())
                .setChest(dto.getChest())
                .setArm(dto.getArm())
                .setHip(dto.getHip())
                .setWeeklyFrequency(dto.getWeeklyFrequency())
                .setInjuryHistory(dto.getInjuryHistory());
        // 2.枚举字段转换
        if (dto.getGoalType() != null) {
            profile.setGoalType(GoalType.of(dto.getGoalType()));
        }
        if (dto.getTrainingLevel() != null) {
            profile.setTrainingLevel(TrainingLevel.of(dto.getTrainingLevel()));
        }
        // 3.BMI 由后端计算，不接受前端传值
        profile.setBmi(calcBmi(dto.getHeight(), dto.getWeight()));

        saveOrUpdate(profile);
        log.info("身体档案已{}：userId={}，BMI={}",
                isNew ? "创建" : "更新", userId, profile.getBmi());
    }

    /**
     * BMI = 体重(kg) / 身高(m)²，保留 1 位小数
     */
    private BigDecimal calcBmi(BigDecimal heightCm, BigDecimal weightKg) {
        if (heightCm == null || weightKg == null
                || heightCm.compareTo(BigDecimal.ZERO) <= 0
                || weightKg.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal heightM = heightCm.divide(HUNDRED, 4, RoundingMode.HALF_UP);
        return weightKg.divide(heightM.multiply(heightM), 1, RoundingMode.HALF_UP);
    }

    private BodyProfileVO toVO(UserBodyProfile p) {
        BodyProfileVO vo = new BodyProfileVO();
        vo.setHeight(p.getHeight())
                .setWeight(p.getWeight())
                .setTargetWeight(p.getTargetWeight())
                .setBmi(p.getBmi())
                .setBodyFatRate(p.getBodyFatRate())
                .setWaist(p.getWaist())
                .setChest(p.getChest())
                .setArm(p.getArm())
                .setHip(p.getHip())
                .setWeeklyFrequency(p.getWeeklyFrequency())
                .setInjuryHistory(p.getInjuryHistory())
                .setUpdateTime(p.getUpdateTime());
        if (p.getGoalType() != null) {
            vo.setGoalType(p.getGoalType().getValue());
            vo.setGoalTypeName(p.getGoalType().getDesc());
        }
        if (p.getTrainingLevel() != null) {
            vo.setTrainingLevel(p.getTrainingLevel().getValue());
            vo.setTrainingLevelName(p.getTrainingLevel().getDesc());
        }
        return vo;
    }
}
