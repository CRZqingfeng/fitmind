package com.fitmind.plan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.plan.constants.PlanConstants;
import com.fitmind.plan.constants.PlanErrorInfo;
import com.fitmind.plan.domain.po.Exercise;
import com.fitmind.plan.domain.query.ExerciseQuery;
import com.fitmind.plan.domain.vo.DictVO;
import com.fitmind.plan.domain.vo.ExerciseDetailVO;
import com.fitmind.plan.domain.vo.ExerciseVO;
import com.fitmind.plan.domain.vo.OptionVO;
import com.fitmind.plan.mapper.ExerciseMapper;
import com.fitmind.plan.service.IExerciseService;
import com.fitmind.plan.utils.LoginUser;
import com.fitmind.plan.utils.PlanJsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 动作库实现。
 *
 * <p><b>本类最容易写错的地方</b>：关键字搜索的写法。
 * {@code .like(name, kw).or().like(enName, kw)} 如果不加 {@code .and(...)} 包一层，
 * {@code or} 会把前面的 category / equipment 条件一起"或"掉 ——
 * 表现是"选了器械=哑铃，结果还是把杠铃的动作搜出来了"，而且很难看出是哪一步错的。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExerciseServiceImpl extends ServiceImpl<ExerciseMapper, Exercise> implements IExerciseService {

    @Override
    public PageDTO<ExerciseVO> queryPage(ExerciseQuery query) {
        LoginUser.id();

        String category = trimToNull(query.getCategory());
        String bodyPart = trimToNull(query.getBodyPart());
        String equipment = trimToNull(query.getEquipment());
        String keyword = trimToNull(query.getKeyword());

        LambdaQueryWrapper<Exercise> wrapper = Wrappers.<Exercise>lambdaQuery()
                .eq(Boolean.TRUE.equals(query.getOnlyOnline()), Exercise::getStatus, 1)
                .eq(category != null, Exercise::getCategory, category)
                .eq(bodyPart != null, Exercise::getBodyPart, bodyPart)
                .eq(equipment != null, Exercise::getEquipment, equipment)
                // ★ 必须用 and(...) 包起来，否则 or 会把上面三个 eq 一起短路掉
                .and(keyword != null, w -> w.like(Exercise::getName, keyword)
                        .or()
                        .like(Exercise::getEnName, keyword))
                .orderByAsc(Exercise::getId);

        Page<Exercise> page = page(query.toMpPage("id", true), wrapper);
        return PageDTO.of(page, this::toVo);
    }

    @Override
    public ExerciseDetailVO queryDetail(Long id) {
        LoginUser.id();

        Exercise exercise = getById(id);
        if (exercise == null || !Integer.valueOf(1).equals(exercise.getStatus())) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.EXERCISE_NOT_EXISTS);
        }

        ExerciseDetailVO vo = new ExerciseDetailVO();
        vo.setId(exercise.getId());
        vo.setName(exercise.getName());
        vo.setEnName(exercise.getEnName());
        vo.setCategory(exercise.getCategory());
        vo.setBodyPart(exercise.getBodyPart());
        vo.setEquipment(exercise.getEquipment());
        vo.setDifficulty(exercise.getDifficulty());
        // 这三个字段数据集里没有，老老实实返回 null —— 前端显示"—"
        vo.setHeat(exercise.getHeat());
        vo.setLumbarPressure(exercise.getLumbarPressure());
        vo.setGifUrl(exercise.getGifUrl());
        vo.setImageUrl(exercise.getImageUrl());
        vo.setPrimaryMuscles(PlanJsonUtils.toList(exercise.getPrimaryMusclesJson()));
        vo.setSecondaryMuscles(PlanJsonUtils.toList(exercise.getSecondaryMusclesJson()));
        vo.setSteps(PlanJsonUtils.toList(exercise.getStepsJson()));
        vo.setTips(exercise.getTips());
        return vo;
    }

    @Override
    public DictVO queryDict() {
        DictVO vo = new DictVO();
        vo.setCategories(PlanConstants.FILTER_CATEGORIES);
        vo.setBodyParts(PlanConstants.BODY_PARTS);
        vo.setEquipments(PlanConstants.EQUIPMENTS);
        vo.setGoals(PlanConstants.GOALS);

        List<OptionVO> difficulties = new ArrayList<>();
        for (int i = 0; i < PlanConstants.DIFFICULTY_LABELS.size(); i++) {
            difficulties.add(new OptionVO(i, PlanConstants.DIFFICULTY_LABELS.get(i)));
        }
        vo.setDifficulties(difficulties);

        List<OptionVO> groupTypes = new ArrayList<>();
        for (int i = 0; i < PlanConstants.GROUP_TYPE_LABELS.size(); i++) {
            groupTypes.add(new OptionVO(i, PlanConstants.GROUP_TYPE_LABELS.get(i)));
        }
        vo.setGroupTypes(groupTypes);

        List<OptionVO> weekdays = new ArrayList<>();
        for (int i = 0; i < PlanConstants.WEEKDAY_LABELS.size(); i++) {
            weekdays.add(new OptionVO(i + 1, PlanConstants.WEEKDAY_LABELS.get(i)));
        }
        vo.setWeekdays(weekdays);
        return vo;
    }

    private ExerciseVO toVo(Exercise exercise) {
        ExerciseVO vo = new ExerciseVO();
        vo.setId(exercise.getId());
        vo.setName(exercise.getName());
        vo.setEnName(exercise.getEnName());
        vo.setCategory(exercise.getCategory());
        vo.setBodyPart(exercise.getBodyPart());
        vo.setEquipment(exercise.getEquipment());
        vo.setDifficulty(exercise.getDifficulty());
        vo.setImageUrl(exercise.getImageUrl());
        return vo;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
