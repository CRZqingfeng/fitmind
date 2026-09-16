package com.fitmind.learning.service.impl;

import com.fitmind.common.constants.MqConstants;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.utils.RabbitMqHelper;
import com.fitmind.common.utils.UserContext;
import com.fitmind.learning.constants.LearningConstants;
import com.fitmind.learning.constants.RedisConstants;
import com.fitmind.learning.domain.vo.SignRecordVO;
import com.fitmind.learning.domain.vo.SignResultVO;
import com.fitmind.common.mq.message.SignInMessage;
import com.fitmind.learning.service.ISignRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 签到实现（Redis BitMap）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignRecordServiceImpl implements ISignRecordService {

    private static final DateTimeFormatter MONTH_SUFFIX = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter DAY_BIZ = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate redisTemplate;
    private final RabbitMqHelper rabbitMqHelper;

    @Override
    public SignResultVO sign() {
        Long userId = UserContext.getUser();
        LocalDate today = LocalDate.now();
        String key = signKey(userId, today);
        int offset = today.getDayOfMonth() - 1;

        // ★ 签到判重靠 SETBIT 的返回值：返回 1 说明这个位本来就是 1（今天签过了）
        //   好处是不需要"先 GETBIT 再 SETBIT"，天然没有并发窗口
        Boolean already = redisTemplate.opsForValue().setBit(key, offset, true);
        if (Boolean.TRUE.equals(already)) {
            throw new BizIllegalException("今天已经签到过了");
        }
        // 位图设 TTL：跨月续接要读上月，所以至少保留两个月
        redisTemplate.expire(key, Duration.ofDays(RedisConstants.SIGN_RECORD_TTL_DAYS));

        // 连续天数（含跨月续接）
        int signDays = countContinuousDays(userId, today);

        // 奖励规则：连续第 7/14/28 天额外奖励
        int reward = switch (signDays) {
            case LearningConstants.SIGN_REWARD_DAY_7 -> LearningConstants.SIGN_REWARD_POINTS_7;
            case LearningConstants.SIGN_REWARD_DAY_14 -> LearningConstants.SIGN_REWARD_POINTS_14;
            case LearningConstants.SIGN_REWARD_DAY_28 -> LearningConstants.SIGN_REWARD_POINTS_28;
            default -> 0;
        };
        int total = LearningConstants.SIGN_BASE_POINTS + reward;

        // 异步加分：签到主流程不等积分的库写入
        rabbitMqHelper.sendAfterCommit(MqConstants.Exchange.LEARNING_EXCHANGE,
                MqConstants.Key.SIGN_IN,
                SignInMessage.of(userId, total, today.format(DAY_BIZ)));

        SignResultVO vo = new SignResultVO();
        vo.setSignDays(signDays);
        vo.setRewardPoints(reward);
        vo.setTotalPoints(total);
        log.info("签到成功：userId={}, 连续 {} 天, 本次 +{}", userId, signDays, total);
        return vo;
    }

    @Override
    public SignRecordVO querySignRecords() {
        Long userId = UserContext.getUser();
        LocalDate today = LocalDate.now();
        int dayOfMonth = today.getDayOfMonth();
        String key = signKey(userId, today);

        // 一次 BITFIELD 取出本月已过的所有位（比逐天 GETBIT 省 30 次往返）
        List<Long> bits = redisTemplate.opsForValue().bitField(key,
                org.springframework.data.redis.connection.BitFieldSubCommands.create()
                        .get(org.springframework.data.redis.connection.BitFieldSubCommands
                                .BitFieldType.unsigned(dayOfMonth)).valueAt(0));

        List<Integer> days = new ArrayList<>();
        if (bits != null && !bits.isEmpty() && bits.get(0) != null) {
            long num = bits.get(0);
            // ⚠️ BITFIELD GET 的语义：读到的第一 bit（offset 0）是<b>最高位</b>，
            //    所以最低 bit 对应的是本月最后一天，解码要从高位往低位走。
            //    （这里踩过坑：按"低 bit = 1 号"解码，1 号永远是唯一结果）
            for (int i = 0; i < dayOfMonth; i++) {
                if ((num & 1L) == 1L) {
                    days.add(dayOfMonth - i);
                }
                num >>= 1;
            }
            days.sort(Integer::compareTo);
        }

        SignRecordVO vo = new SignRecordVO();
        vo.setDays(days);
        vo.setContinuousDays(countContinuousDays(userId, today));
        return vo;
    }

    // ==================== 内部方法 ====================

    /**
     * 连续签到天数。
     *
     * <p>先数本月：从今天往回逐位查，遇到 0 停。
     * 若<b>本月每一天都签了</b>，说明连续记录跨到了上月，继续接上月月末的连续天数。</p>
     *
     * <p>常见实现不做这一步，导致<b>每月 1 号连续签到必然断掉</b>——
     * "连续 30 天打卡"永远无法达成。</p>
     */
    private int countContinuousDays(Long userId, LocalDate today) {
        String key = signKey(userId, today);
        int days = countBackwards(key, today.getDayOfMonth() - 1);

        if (days == today.getDayOfMonth()) {
            LocalDate lastMonth = today.minusMonths(1);
            String lastKey = signKey(userId, lastMonth);
            // 只看上月末的连续段（不再继续往上月之前追溯，够用且成本可控）
            days += countBackwards(lastKey, lastMonth.lengthOfMonth() - 1);
        }
        return days;
    }

    /** 从 startOffset 往回数连续为 1 的位数 */
    private int countBackwards(String key, int startOffset) {
        int count = 0;
        for (int offset = startOffset; offset >= 0; offset--) {
            if (!Boolean.TRUE.equals(redisTemplate.opsForValue().getBit(key, offset))) {
                break;
            }
            count++;
        }
        return count;
    }

    /** key 形如 sign:uid:12:202609 —— 一个月一个位图，天然分片 */
    private String signKey(Long userId, LocalDate date) {
        return RedisConstants.SIGN_RECORD_KEY_PREFIX + userId + ":" + date.format(MONTH_SUFFIX);
    }
}
