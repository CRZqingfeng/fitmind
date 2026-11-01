package com.fitmind.plan.utils;

import com.fitmind.plan.constants.PlanConstants;
import com.fitmind.plan.domain.po.Exercise;
import com.fitmind.plan.domain.po.Plan;
import com.fitmind.plan.domain.po.PlanDay;
import com.fitmind.plan.domain.po.PlanDayItem;
import com.fitmind.plan.domain.vo.PlanCardVO;
import com.fitmind.plan.domain.vo.PlanDayItemVO;
import com.fitmind.plan.domain.vo.PlanDayVO;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * PO → VO 装配。
 *
 * <p><b>为什么把展示串集中在这里</b>："共4组·每组8次·组休60秒"、"7个动作·预计43分钟"、
 * "一周5练 · 增肌 · 进阶" 这些串前端一旦自己拼，就会和后端口径分叉：
 * 后端把组休从 60 改成 90，前端还显示 60，而两个人的数据其实是对的。
 * 统一由这里产出，前端只负责显示。</p>
 *
 * <p>{@code weightJin}（斤）也只在这里换算一次：库里全存 kg，界面要斤，×2 只此一处。</p>
 */
public final class PlanVoAssembler {

    /** 斤 = 公斤 × 2 */
    private static final BigDecimal JIN_PER_KG = BigDecimal.valueOf(2);

    private PlanVoAssembler() {
    }

    /**
     * "共4组·每组8次·组休60秒" / "共3组·每组60秒" / "共2组·每组30秒（拉伸）"。
     *
     * <p>计时型动作（平板支撑、跳绳）没有"次数"，显示"{@code 每组60秒}"；
     * 组休为 0 的（拉伸）不显示"组休0秒"——那句话既没用又显得像漏配。</p>
     */
    public static String prescription(PlanDayItem item) {
        int sets = nz(item.getSets());
        int reps = nz(item.getReps());
        int duration = nz(item.getDurationSec());
        int rest = nz(item.getRestSec());

        StringBuilder sb = new StringBuilder();
        sb.append("共").append(sets).append("组");
        if (duration > 0) {
            sb.append("·每组").append(duration).append("秒");
        } else {
            sb.append("·每组").append(reps).append("次");
        }
        if (rest > 0) {
            sb.append("·组休").append(rest).append("秒");
        }
        return sb.toString();
    }

    /** 公斤 → 斤（×2；null 安全）。★ 不要 stripTrailingZeros —— 会把 40.00 变成 4E+1，JSON 里很难看 */
    public static BigDecimal toJin(BigDecimal kg) {
        if (kg == null) {
            return null;
        }
        return kg.multiply(JIN_PER_KG).setScale(1, java.math.RoundingMode.HALF_UP);
    }

    public static PlanDayItemVO toItemVo(PlanDayItem item, Exercise exercise) {
        PlanDayItemVO vo = new PlanDayItemVO();
        vo.setId(item.getId());
        vo.setExerciseId(item.getExerciseId());
        vo.setExerciseName(exercise == null ? "动作已下架" : exercise.getName());
        vo.setImageUrl(exercise == null ? null : exercise.getImageUrl());
        vo.setCategory(exercise == null ? null : exercise.getCategory());
        vo.setBodyPart(exercise == null ? null : exercise.getBodyPart());
        vo.setEquipment(exercise == null ? null : exercise.getEquipment());
        vo.setSort(item.getSort());
        vo.setGroupType(item.getGroupType());
        vo.setGroupTypeLabel(PlanConstants.groupTypeLabel(item.getGroupType()));
        vo.setSets(item.getSets());
        vo.setReps(item.getReps());
        vo.setDurationSec(item.getDurationSec());
        vo.setWeightKg(item.getWeightKg());
        vo.setWeightJin(toJin(item.getWeightKg()));
        vo.setRestSec(item.getRestSec());
        vo.setPrescription(prescription(item));
        return vo;
    }

    /**
     * 训练日 VO。
     *
     * <p>{@code summary} 就是设计稿那行字。<b>空训练日给的是"还没有动作"而不是"0个动作·预计0分钟"</b> ——
     * 后者看起来像算错了，前者能告诉用户下一步该干什么。</p>
     */
    public static PlanDayVO toDayVo(PlanDay day, List<PlanDayItem> items, Map<Long, Exercise> exerciseMap) {
        PlanDayVO vo = new PlanDayVO();
        vo.setId(day.getId());
        vo.setDayIndex(day.getDayIndex());
        vo.setWeekday(day.getWeekday());
        vo.setWeekdayLabel(PlanConstants.weekdayLabel(day.getWeekday()));
        vo.setName(day.getName());
        vo.setItemCount(items.size());
        vo.setEstMinutes(nz(day.getEstMinutes()));
        vo.setSummary(items.isEmpty()
                ? "还没有动作"
                : items.size() + "个动作·预计" + nz(day.getEstMinutes()) + "分钟");
        vo.setItems(items.stream()
                .sorted(Comparator.comparing(PlanDayItem::getSort, Comparator.nullsLast(Integer::compareTo)))
                .map(item -> toItemVo(item, exerciseMap.get(item.getExerciseId())))
                .toList());
        return vo;
    }

    /** 计划卡片：副标题后端拼好，避免前端三个地方各拼一次、格式还不一样 */
    public static PlanCardVO toCardVo(Plan plan) {
        PlanCardVO vo = new PlanCardVO();
        vo.setId(plan.getId());
        vo.setTitle(plan.getTitle());
        vo.setSummary(plan.getSummary());
        vo.setCoverUrl(plan.getCoverUrl());
        vo.setGoal(plan.getGoal());
        vo.setDifficulty(plan.getDifficulty());
        vo.setDifficultyLabel(PlanConstants.difficultyLabel(plan.getDifficulty()));
        vo.setTrainDaysPerWeek(nz(plan.getTrainDaysPerWeek()));
        vo.setKcalEstimate(nz(plan.getKcalEstimate()));
        vo.setEquipment(PlanJsonUtils.toList(plan.getEquipmentJson()));
        vo.setSource(plan.getSource());
        vo.setSubtitle("一周" + nz(plan.getTrainDaysPerWeek()) + "练 · " + plan.getGoal()
                + " · " + PlanConstants.difficultyLabel(plan.getDifficulty()));
        return vo;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
