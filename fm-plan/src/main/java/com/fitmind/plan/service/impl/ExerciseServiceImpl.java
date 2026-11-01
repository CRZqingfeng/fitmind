package com.fitmind.plan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import com.fitmind.plan.utils.AssetUrls;
import com.fitmind.plan.utils.DictOrder;
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
        // ★ DB 存对象 key，出参给绝对地址（唯一换算点见 AssetUrls）
        vo.setImageUrl(AssetUrls.toUrl(exercise.getImageUrl()));
        vo.setPrimaryMuscles(PlanJsonUtils.toList(exercise.getPrimaryMusclesJson()));
        vo.setSecondaryMuscles(PlanJsonUtils.toList(exercise.getSecondaryMusclesJson()));
        vo.setSteps(PlanJsonUtils.toList(exercise.getStepsJson()));
        vo.setTips(exercise.getTips());
        return vo;
    }

    @Override
    public DictVO queryDict() {
        DictVO vo = new DictVO();
        // ★★ 2026-11-01（P3）起字典**动态化**，与食物字典同一口径：
        //    只返回库里真有数据的值。原因：全量 873 条后
        //      · "热身" 仍然 0 条（数据集没有这个分类，不编）
        //      · "史密斯/悍马机" 仍然 0 条（数据集没有这两类机器）
        //    写死常量的话，左侧栏会留 3 个**永远空的死入口**，用户点了以为是 bug。
        //    ⚠️ 别把这里改回常量：那样"点进去空列表"会重新出现，而且不会有任何报错。
        List<String> categories = DictOrder.order(
                distinctColumn("category").stream()
                        // "力量"按**部位**切，它自己不是一级筛选（见 PlanConstants 类注释）
                        .filter(c -> !PlanConstants.CATEGORY_STRENGTH.equals(c))
                        .toList(),
                PlanConstants.FILTER_CATEGORIES);
        vo.setCategories(categories);
        vo.setBodyParts(DictOrder.order(distinctColumn("body_part"), PlanConstants.BODY_PARTS));
        vo.setEquipments(DictOrder.order(distinctColumn("equipment"), PlanConstants.EQUIPMENTS));
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

    /**
     * 某个列在**上架动作**里的全部取值（DISTINCT）。
     *
     * <p>★ 只查 status=1：下架的动作不该出现在筛选条件里（点进去必然是空列表）。</p>
     */
    private List<Object> distinctColumn(String column) {
        return baseMapper.selectObjs(new QueryWrapper<Exercise>()
                .select("DISTINCT `" + column + "`")
                .eq("status", 1));
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
        vo.setImageUrl(AssetUrls.toUrl(exercise.getImageUrl()));
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
