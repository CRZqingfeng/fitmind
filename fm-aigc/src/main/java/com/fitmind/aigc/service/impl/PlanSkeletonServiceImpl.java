package com.fitmind.aigc.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitmind.aigc.agent.AgentContext;
import com.fitmind.aigc.agent.PlanAgent;
import com.fitmind.aigc.agent.AgentContext.RetrievalOptions;
import com.fitmind.aigc.constants.AigcConstants;
import com.fitmind.aigc.domain.dto.PlanSkeletonDTO;
import com.fitmind.aigc.domain.vo.PlanSkeletonVO;
import com.fitmind.aigc.limit.ChatQuotaLimiter;
import com.fitmind.aigc.limit.QuotaResult;
import com.fitmind.aigc.rag.RagTrace;
import com.fitmind.aigc.service.BodyProfileService;
import com.fitmind.aigc.service.IPlanSkeletonService;
import com.fitmind.aigc.tool.ToolTrace;
import com.fitmind.api.dto.user.UserBodyProfileDTO;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.utils.TokenContext;
import com.fitmind.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 骨架生成实现。
 *
 * <p><b>本类最容易写错的三处：</b></p>
 * <ol>
 *   <li><b>把模型的输出直接透传</b>：模型经常加 ```json 代码块、加一句"好的，这是你的计划："，
 *       前端 `JSON.parse` 直接炸。这里必须抽出第一个 <code>{</code> 到最后一个 <code>}</code>。</li>
 *   <li><b>不校验就信</b>：{@code weeklyFrequency=99}、{@code ratio} 和 = 0.9、
 *       {@code division="全身训练"} 都会让下游排不出计划。这里做<b>结构校验</b>，
 *       不合规就整份降级成规则骨架（不在半份脏数据上继续）。</li>
 *   <li><b>失败就抛异常</b>：那用户点「定制计划」会看到一个错误页。
 *       降级是正常业务态 —— 用户要的是"一份能用的计划"，不是"AI 必须成功"。</li>
 * </ol>
 *
 * <p>★ <b>不接收 userId</b>：一律 {@code UserContext.getUser()}，与 {@code BodyTools} 同一条红线。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanSkeletonServiceImpl implements IPlanSkeletonService {

    /** 每周训练次数兜底值：一周三练是"能坚持下来"的最大公约数 */
    private static final int DEFAULT_FREQUENCY = 3;
    private static final int MAX_ADVICE_CHARS = 120;

    private final PlanAgent planAgent;
    private final BodyProfileService bodyProfileService;
    private final ChatQuotaLimiter quotaLimiter;
    private final ObjectMapper objectMapper;

    // ==================== 主流程 ====================

    @Override
    public PlanSkeletonVO skeleton(PlanSkeletonDTO dto) {
        Long userId = UserContext.getUser();
        if (userId == null) {
            // 白名单漏配时才会走到这里（/plan/** 必须加进 includeLoginPaths）
            throw new BizIllegalException(401, "请先登录");
        }
        PlanSkeletonDTO req = dto == null ? new PlanSkeletonDTO() : dto;

        UserBodyProfileDTO profile = bodyProfileService.load(userId);
        Facts facts = Facts.of(profile, req);
        if (facts.weightKg() == null) {
            // 连体重都不知道：不编骨架，让前端引导去填档案 / 记体重
            log.info("骨架请求被拒（没有任何体重数据）：userId={}", userId);
            PlanSkeletonVO vo = new PlanSkeletonVO();
            vo.setSkeletonId(UUID.randomUUID().toString());
            vo.setNeedProfile(true);
            vo.setDegraded(false);
            return vo;
        }

        QuotaResult quota = quotaLimiter.acquire(userId);
        if (quota.limited()) {
            throw new BizIllegalException(400,
                    AigcConstants.limitMessage(quota.reason(), quota.limit()));
        }

        // 降级/失败时要退还这次额度：用户并没有得到"AI 的服务"
        boolean refund = true;
        try {
            AgentContext context = new AgentContext(
                    "plan-" + userId,
                    UUID.randomUUID().toString(),
                    userId,
                    question(facts, req),
                    RetrievalOptions.none(),
                    new RagTrace(),
                    new ToolTrace(2),
                    profile,
                    TokenContext.getToken(),
                    // ★ 骨架请求不带历史：它不是一个"对话"，把聊天记录塞进来只会干扰 JSON 输出
                    List.of());

            String answer = planAgent.complete(context);
            Parsed parsed = parse(answer);
            if (parsed != null) {
                refund = false;
                log.info("骨架生成成功：userId={}, division={}, freq={}, intensity={}, 餐数={}",
                        userId, parsed.division(), parsed.weeklyFrequency(), parsed.intensity(),
                        parsed.mealSplit().size());
                return toVo(parsed, false);
            }
            log.warn("骨架解析失败，降级为规则骨架：userId={}, 模型输出={}", userId, brief(answer));
        } catch (Exception e) {
            log.warn("骨架生成失败，降级为规则骨架：userId={}, 原因={}", userId, e.getMessage());
        } finally {
            // 无论成功/失败/降级都结算：不结算的话并发额度会被这一轮永久占住
            quotaLimiter.settle(userId, 0, refund);
        }

        return toVo(ruleSkeleton(facts, req), true);
    }

    // ==================== 提问文本 ====================

    /**
     * 把"用户数据 + 诉求"拼成一次提问。
     *
     * <p>★ 为什么要拼成自然语言而不是丢个 JSON 给模型：模型读结构化文本更稳，
     * 而且这里可以把"这个字段意味着什么"写进去（BMI 用中国标准、周频影响分化）——
     * 那正是模型最容易想当然的地方。</p>
     */
    private String question(Facts f, PlanSkeletonDTO req) {
        StringBuilder sb = new StringBuilder("请为下面这位用户选一份训练与饮食骨架。\n");
        sb.append("- 当前体重：").append(plain(f.weightKg())).append(" kg\n");
        if (f.targetWeightKg() != null) {
            sb.append("- 目标体重：").append(plain(f.targetWeightKg())).append(" kg（")
                    .append(f.goalLabel()).append("）\n");
        }
        if (f.heightCm() != null) {
            sb.append("- 身高：").append(plain(f.heightCm())).append(" cm\n");
            if (f.bmi() != null) {
                sb.append("- BMI：").append(plain(f.bmi())).append("（中国标准：<18.5 偏瘦 / 18.5~24 正常 / 24~28 偏胖 / ≥28 肥胖）\n");
            }
        }
        sb.append("- 训练目标：").append(f.goalLabel()).append('\n');
        sb.append("- 训练水平：").append(levelLabel(f.trainingLevel())).append('\n');
        if (f.weeklyFrequency() != null) {
            sb.append("- 每周可训练：").append(f.weeklyFrequency()).append(" 次\n");
        }
        if (f.injury() != null && !f.injury().isBlank()) {
            sb.append("- 伤病史：").append(f.injury()).append('\n');
        }
        if (req.getNote() != null && !req.getNote().isBlank()) {
            sb.append("- 补充诉求：").append(req.getNote().trim()).append('\n');
        }
        sb.append("\n只输出 JSON，不要任何解释文字。");
        return sb.toString();
    }

    // ==================== 解析与校验 ====================

    /** 解析后的骨架（已经过校验，可以直接用） */
    private record Parsed(String division, int weeklyFrequency, String intensity,
                          List<PlanSkeletonVO.MealSplitVO> mealSplit,
                          String dietAdvice, String trainingAdvice, String targetTip) {
    }

    /**
     * 解析模型输出。
     *
     * @return null = 结构不合规（调用方会降级），<b>不抛异常</b>
     */
    private Parsed parse(String answer) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        String json = extractJson(answer);
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String division = text(node, "division");
            if (!List.of("三分化", "五分化", "上下肢分化").contains(division)) {
                return null;
            }
            int freq = node.path("weeklyFrequency").asInt(-1);
            if (freq < 1 || freq > 7) {
                return null;
            }
            String intensity = text(node, "intensity");
            if (!List.of("LOW", "MEDIUM", "HIGH").contains(intensity)) {
                return null;
            }
            List<PlanSkeletonVO.MealSplitVO> split = parseSplit(node.path("mealSplit"));
            if (split == null) {
                return null;
            }
            return new Parsed(division, freq, intensity, split,
                    clip(text(node, "dietAdvice")),
                    clip(text(node, "trainingAdvice")),
                    clip(text(node, "targetTip")));
        } catch (Exception e) {
            log.debug("骨架 JSON 解析异常：{}", e.getMessage());
            return null;
        }
    }

    /** 餐次比例：类型 1~4、比例 (0,1]、总数 1~8、同类型 ≤3、和 = 1（±0.01） */
    private List<PlanSkeletonVO.MealSplitVO> parseSplit(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > 8) {
            return null;
        }
        List<PlanSkeletonVO.MealSplitVO> list = new ArrayList<>(node.size());
        int[] perType = new int[5];
        double sum = 0;
        for (JsonNode item : node) {
            int type = item.path("mealType").asInt(-1);
            double ratio = item.path("ratio").asDouble(-1);
            if (type < 1 || type > 4 || ratio <= 0 || ratio > 1) {
                return null;
            }
            if (++perType[type] > 3) {
                return null;
            }
            list.add(new PlanSkeletonVO.MealSplitVO(type, ratio));
            sum += ratio;
        }
        if (sum < 0.99 || sum > 1.01) {
            return null;
        }
        return list;
    }

    /**
     * 从模型输出里抽出 JSON 主体。
     *
     * <p>★ 必须做这一步：模型特别喜欢 ```json 代码块和"好的，这是你的计划："，
     * 而前端 `JSON.parse` 不接受这些前缀。</p>
     */
    private String extractJson(String answer) {
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return answer.substring(start, end + 1);
    }

    // ==================== 规则骨架（降级） ====================

    private Parsed ruleSkeleton(Facts f, PlanSkeletonDTO req) {
        int freq = f.weeklyFrequency() != null ? f.weeklyFrequency() : DEFAULT_FREQUENCY;
        String division = freq >= 5 ? "五分化" : freq == 4 ? "上下肢分化" : "三分化";
        boolean loss = "减脂".equals(f.goalLabel());
        String intensity = loss ? "LOW" : "MEDIUM";

        // 增肌按每日六餐（三次加餐），减脂按四餐
        List<PlanSkeletonVO.MealSplitVO> split = new ArrayList<>();
        if (loss) {
            split.add(new PlanSkeletonVO.MealSplitVO(1, 0.30));
            split.add(new PlanSkeletonVO.MealSplitVO(2, 0.40));
            split.add(new PlanSkeletonVO.MealSplitVO(3, 0.25));
            split.add(new PlanSkeletonVO.MealSplitVO(4, 0.05));
        } else {
            split.add(new PlanSkeletonVO.MealSplitVO(1, 0.25));
            split.add(new PlanSkeletonVO.MealSplitVO(4, 0.05));
            split.add(new PlanSkeletonVO.MealSplitVO(2, 0.30));
            split.add(new PlanSkeletonVO.MealSplitVO(4, 0.05));
            split.add(new PlanSkeletonVO.MealSplitVO(3, 0.30));
            split.add(new PlanSkeletonVO.MealSplitVO(4, 0.05));
        }

        return new Parsed(division, freq, intensity, split,
                "按你的体重与目标配餐，" + (loss ? "把主食换成粗粮、蛋白吃够" : "少食多餐更容易吃够蛋白") + "。",
                String.format("一周 %d 练，按%s安排；强度%s，先把动作做标准再加重量。",
                        freq, division, "LOW".equals(intensity) ? "偏轻" : "中等"),
                f.targetWeightKg() == null
                        ? "先在「我的 → 身体档案」填一个目标体重，计划会更贴合你。"
                        : "目标 " + plain(f.targetWeightKg()) + " kg，按每周 0.25~0.75 kg 的节奏推进更稳。");
    }

    private PlanSkeletonVO toVo(Parsed p, boolean degraded) {
        PlanSkeletonVO vo = new PlanSkeletonVO();
        vo.setSkeletonId(UUID.randomUUID().toString());
        vo.setNeedProfile(false);
        vo.setDegraded(degraded);
        vo.setDivision(p.division());
        vo.setWeeklyFrequency(p.weeklyFrequency());
        vo.setIntensity(p.intensity());
        vo.setMealSplit(p.mealSplit());
        vo.setDietAdvice(p.dietAdvice());
        vo.setTrainingAdvice(p.trainingAdvice());
        vo.setTargetTip(p.targetTip());
        return vo;
    }

    // ==================== 用户数据 ====================

    /**
     * 本次请求能拿到的用户数据。
     *
     * <p>★ 优先档案；档案读不到（本机 fm-user 没起）才用请求里带过来的体重 ——
     * 那两项是 fm-plan 用 {@code user_body_metric} 算出来的真实数，不是编的。</p>
     */
    private record Facts(BigDecimal weightKg, BigDecimal targetWeightKg, BigDecimal heightCm,
                         BigDecimal bmi, Integer goalType, Integer weeklyFrequency,
                         Integer trainingLevel, String injury) {

        static Facts of(UserBodyProfileDTO profile, PlanSkeletonDTO req) {
            BigDecimal weight = profile != null && profile.getWeight() != null
                    ? profile.getWeight() : req.getWeightKg();
            BigDecimal target = profile != null && profile.getTargetWeight() != null
                    ? profile.getTargetWeight() : req.getTargetWeightKg();
            BigDecimal height = profile != null && profile.getHeight() != null
                    ? profile.getHeight() : req.getHeightCm();
            BigDecimal bmi = profile != null && profile.getBmi() != null ? profile.getBmi() : null;
            Integer goal = profile != null && profile.getGoalType() != null
                    ? profile.getGoalType() : req.getGoalType();
            Integer freq = req.getWeeklyFrequency() != null
                    ? req.getWeeklyFrequency()
                    : profile != null ? profile.getWeeklyFrequency() : null;
            Integer level = profile != null && profile.getTrainingLevel() != null
                    ? profile.getTrainingLevel() : req.getTrainingLevel();
            String injury = profile == null ? null : profile.getInjuryHistory();
            return new Facts(weight, target, height, bmi, goal, freq, level, injury);
        }

        /** 目标名：优先档案里的 goalType，其次按"目标体重 vs 当前体重"推 */
        String goalLabel() {
            if (goalType != null) {
                return switch (goalType) {
                    case 1 -> "增肌";
                    case 2 -> "减脂";
                    case 3 -> "塑形";
                    default -> "保持";
                };
            }
            if (targetWeightKg != null && weightKg != null) {
                int cmp = targetWeightKg.compareTo(weightKg);
                return cmp < 0 ? "减脂" : cmp > 0 ? "增肌" : "保持";
            }
            return "增肌";
        }
    }

    // ==================== 小工具 ====================

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private String clip(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() <= MAX_ADVICE_CHARS ? t : t.substring(0, MAX_ADVICE_CHARS);
    }

    private String brief(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ");
        return t.length() <= 200 ? t : t.substring(0, 200) + "…";
    }

    private String plain(BigDecimal v) {
        return v == null ? "-" : v.stripTrailingZeros().toPlainString();
    }

    private String levelLabel(Integer level) {
        if (level == null) {
            return "未填写（按零基础处理）";
        }
        return switch (level) {
            case 1 -> "零基础";
            case 2 -> "初级";
            case 3 -> "中级";
            case 4 -> "高级";
            default -> "未填写";
        };
    }
}
