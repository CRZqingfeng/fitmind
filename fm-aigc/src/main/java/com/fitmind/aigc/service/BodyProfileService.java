package com.fitmind.aigc.service;

import com.fitmind.api.client.user.UserClient;
import com.fitmind.api.dto.user.UserBodyProfileDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 用户健身档案 —— AI 教练的"用户上下文"来源。
 *
 * <p>★ <b>这是 M5 那句"体测数据将成为 M6 的关键上下文"的兑现。</b>
 * 一份体测档案同时服务两件事，而且都是 M6 的关键能力：</p>
 * <ol>
 *   <li><b>RAG 的元数据过滤</b>：训练目标决定检索时排除哪一类知识文档
 *       （减脂用户不该拿到"增肌期怎么吃"）；</li>
 *   <li><b>工具调用的个性化</b>：模型可以读它、引用它，说出
 *       "你体脂 15.5%、每周练 4 次，先从这门哑铃全身训练开始" 这种话 ——
 *       而不是给一段放之四海皆准的套话。</li>
 * </ol>
 *
 * <p>★★ <b>一个刻意的实现选择：每轮对话只取一次，两个消费方共用。</b>
 * 它由编排层在发起对话前拉取，然后<b>传下去</b>（而不是让 RAG 拦截器和工具各自去 Feign 一次）。
 * 理由：① 同一份数据在一次请求里查两遍是浪费；② 更重要的，
 * <b>两处拿到的必须是同一份档案</b> —— 如果各自查询，就存在"过滤器用了旧档案、
 * 模型看到的是新档案"这种不一致，而它极难被发现。</p>
 *
 * <p>⚠️ 方法统一 {@code userId} 入参，这个 {@code userId} 只能来自
 * {@code UserContext}（由编排层传入），<b>永远不来自请求参数或模型输出</b>。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BodyProfileService {

    /** fm_user.goal_type 的枚举值 → 知识库元数据里的 goal */
    private static final int GOAL_MUSCLE_GAIN = 1;
    private static final int GOAL_FAT_LOSS = 2;

    private final UserClient userClient;

    /**
     * 拉取档案。
     *
     * <p>★ 拿不到档案<b>不是错误</b>：用户可以不填（新用户一定有这个过程）。
     * 所以失败一律返回 null，由调用方退化成"无个性化" ——
     * 一个可选的个性化能力，不该让"问个健身知识"这件事失败。</p>
     */
    public UserBodyProfileDTO load(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            UserBodyProfileDTO profile = userClient.queryBodyProfile(userId);
            if (profile == null) {
                log.debug("用户 {} 尚未填写健身档案，本轮不做个性化", userId);
            }
            return profile;
        } catch (Exception e) {
            // 服务间调用失败：记日志、返回 null。★ 不抛异常 ——
            // 档案服务挂了不该连带"AI 教练不可用"，那会把影响面从一个功能放大到整个功能
            log.warn("查询健身档案失败（本轮退化为无个性化）：userId={}，原因={}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * 推导知识库检索用的 goal（用于元数据过滤）。
     *
     * <p>★ 优先用用户<b>自己填的</b> {@code goalType}；
     * 只有在它为空时才退回"目标体重 vs 当前体重"的推导。
     * 顺序不能反：推导会算错两种情况 ——
     * 想同时增肌减脂的（目标体重 = 当前体重）、以及没填目标体重的
     * （直接推导不出来，个性化就丢了）。</p>
     */
    public String deriveGoal(UserBodyProfileDTO profile) {
        if (profile == null) {
            return null;
        }
        Integer goalType = profile.getGoalType();
        if (goalType != null) {
            if (goalType == GOAL_MUSCLE_GAIN) {
                return "muscle_gain";
            }
            if (goalType == GOAL_FAT_LOSS) {
                return "fat_loss";
            }
            // 3-塑形 / 4-保持 → 通用知识库文档（不做过窄的过滤：
            // 过滤过度会让召回率掉得比准确率涨得快，见 KnowledgeRetriever 的说明）
            return "general";
        }
        // 兜底推导
        if (profile.getWeight() != null && profile.getTargetWeight() != null) {
            int cmp = profile.getTargetWeight().compareTo(profile.getWeight());
            if (cmp > 0) {
                return "muscle_gain";
            }
            if (cmp < 0) {
                return "fat_loss";
            }
        }
        return null;
    }

    /**
     * 把档案转成模型能直接读的自然语言。
     *
     * <p>★ 为什么要转成文字而不是给模型一个 JSON：模型读文本更稳，
     * 而且这里可以顺手把"这个字段意味着什么"写进去
     * （BMI 用中国标准、TDEE 的活动系数来自每周训练次数）——
     * 那正是模型最容易想当然的地方。</p>
     */
    public String describe(UserBodyProfileDTO profile) {
        if (profile == null) {
            return "（该用户还没有填写健身档案。需要身高体重、训练目标或训练频次时，"
                    + "请先告诉用户去「我的 → 身体档案」补全，不要凭空假设这些数据。）";
        }
        StringBuilder sb = new StringBuilder("【当前用户的健身档案】\n");
        append(sb, "身高", profile.getHeight(), "cm");
        append(sb, "当前体重", profile.getWeight(), "kg");
        append(sb, "BMI", profile.getBmi(), "");
        append(sb, "体脂率", profile.getBodyFatRate(), "%");
        append(sb, "目标体重", profile.getTargetWeight(), "kg");
        if (profile.getGoalType() != null) {
            sb.append("- 训练目标：").append(goalName(profile.getGoalType())).append('\n');
        }
        if (profile.getTrainingLevel() != null) {
            sb.append("- 训练水平：").append(levelName(profile.getTrainingLevel())).append('\n');
        }
        if (profile.getWeeklyFrequency() != null) {
            sb.append("- 每周可训练：").append(profile.getWeeklyFrequency()).append(" 次\n");
        }
        if (profile.getInjuryHistory() != null && !profile.getInjuryHistory().isBlank()) {
            sb.append("- 伤病史：").append(profile.getInjuryHistory()).append('\n');
        }
        sb.append("（以上是用户自己填写的档案，可直接引用；"
                + "不要用模型自己的推算去覆盖它。）");
        return sb.toString();
    }

    private void append(StringBuilder sb, String label, Object value, String unit) {
        if (value != null) {
            sb.append("- ").append(label).append('：').append(value).append(unit).append('\n');
        }
    }

    private String goalName(int goalType) {
        return switch (goalType) {
            case 1 -> "增肌";
            case 2 -> "减脂";
            case 3 -> "塑形";
            case 4 -> "保持";
            default -> "未知";
        };
    }

    private String levelName(int level) {
        return switch (level) {
            case 1 -> "零基础";
            case 2 -> "初级";
            case 3 -> "中级";
            case 4 -> "高级";
            default -> "未知";
        };
    }
}
