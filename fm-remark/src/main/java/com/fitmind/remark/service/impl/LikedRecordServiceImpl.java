package com.fitmind.remark.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.api.dto.remark.LikedTimesDTO;
import com.fitmind.common.constants.MqConstants;
import com.fitmind.common.utils.RabbitMqHelper;
import com.fitmind.common.utils.UserContext;
import com.fitmind.remark.constants.RemarkConstants;
import com.fitmind.remark.domain.dto.LikeRecordFormDTO;
import com.fitmind.remark.domain.po.LikedRecord;
import com.fitmind.remark.mapper.LikedRecordMapper;
import com.fitmind.remark.service.ILikedRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 点赞服务实现。
 *
 * <p><b>写路径（快）</b>：Redis Set 判重 + 计数，落库明细做永久凭证。</p>
 * <p><b>同步路径（异步）</b>：脏标 Set → XXL-JOB 批量弹出 → 聚合 MQ → 业务方覆盖更新。</p>
 *
 * <p><b>为什么不追求"精确一次"</b>：业务方的更新是<b>覆盖</b>语义（{@code set liked_times = N}），
 * 重复投递只会覆盖成同一个值 —— 天然幂等，零成本。
 * 对比积分（累加语义）就必须靠唯一索引做幂等。这条差异是本模块最值得讲的设计点。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikedRecordServiceImpl extends ServiceImpl<LikedRecordMapper, LikedRecord>
        implements ILikedRecordService {

    private final StringRedisTemplate redisTemplate;

    private final RabbitMqHelper rabbitMqHelper;

    @Override
    public void addLikeRecord(LikeRecordFormDTO dto) {
        // 1.按前端传的 liked 走 like「点赞」或 unlike「取消」——两者的共同点是：
        //   返回值表示"本次是否真的产生了变更"，false 就说明是重复操作
        boolean effective = Boolean.TRUE.equals(dto.getLiked()) ? like(dto) : unlike(dto);
        if (!effective) {
            log.debug("无实际变更（幂等空操作），跳过标脏：{}", dto);
            return;
        }
        // 2.标脏：等着同步任务把最新计数推给业务方
        markDirty(dto.getBizType(), dto.getBizId());
    }

    @Override
    public Set<Long> isBizLiked(String bizType, List<Long> bizIds) {
        if (bizIds == null || bizIds.isEmpty()) {
            return Set.of();
        }
        Long userId = UserContext.getUser();
        if (userId == null) {
            return Set.of();
        }
        String member = userId.toString();
        // ★ pipeline：一次网络往返发完所有 SISMEMBER。
        //   逐个查的话 20 条内容就是 20 次 RTT —— 列表页最容易被这种"看不见的 N+1"拖慢。
        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection src = (StringRedisConnection) connection;
            for (Long bizId : bizIds) {
                src.sIsMember(RemarkConstants.likesSetKey(bizType, bizId), member);
            }
            return null;
        });
        Set<Long> liked = new HashSet<>();
        for (int i = 0; i < bizIds.size() && i < results.size(); i++) {
            if (Boolean.TRUE.equals(results.get(i))) {
                liked.add(bizIds.get(i));
            }
        }
        return liked;
    }

    @Override
    public int syncLikedTimes(String bizType, int batchSize) {
        // 1.原子弹出脏标：SPOP 是"取出并删除"，多实例并发跑同一任务也不会重复处理同一条
        List<String> popped = redisTemplate.opsForSet().pop(RemarkConstants.likesDirtyKey(bizType), batchSize);
        if (popped == null || popped.isEmpty()) {
            return 0;
        }
        List<Long> bizIds = popped.stream().map(Long::valueOf).toList();

        // 2.批量取最新计数（同样用 pipeline：Set 基数即点赞数，SCARD 是 O(1)）
        List<Object> counts = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection src = (StringRedisConnection) connection;
            for (Long bizId : bizIds) {
                src.sCard(RemarkConstants.likesSetKey(bizType, bizId));
            }
            return null;
        });

        // 3.组装成一条聚合消息（30 条变更 → 1 条 MQ，而不是 30 条）
        List<LikedTimesDTO> list = new ArrayList<>(bizIds.size());
        for (int i = 0; i < bizIds.size(); i++) {
            int likedTimes = 0;
            if (i < counts.size() && counts.get(i) instanceof Number number) {
                likedTimes = number.intValue();
            }
            list.add(LikedTimesDTO.of(bizIds.get(i), likedTimes));
        }
        rabbitMqHelper.send(MqConstants.Exchange.LIKE_EXCHANGE,
                String.format(MqConstants.Key.LIKE_TIMES_KEY_TEMPLATE, bizType), list);
        log.debug("已同步 {} 条 {} 点赞数到业务方", list.size(), bizType);
        return list.size();
    }

    @Override
    public int rebuild(String bizType, List<Long> bizIds) {
        if (bizIds == null || bizIds.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Long bizId : bizIds) {
            List<LikedRecord> rows = lambdaQuery()
                    .eq(LikedRecord::getBizType, bizType)
                    .eq(LikedRecord::getBizId, bizId)
                    .list();
            String key = RemarkConstants.likesSetKey(bizType, bizId);
            redisTemplate.delete(key);
            if (!rows.isEmpty()) {
                String[] members = rows.stream()
                        .map(r -> r.getUserId().toString())
                        .toArray(String[]::new);
                redisTemplate.opsForSet().add(key, members);
            }
            // 重建完顺手标脏，让业务表的冗余计数也跟上
            markDirty(bizType, bizId);
            count++;
        }
        log.warn("已从 DB 重建 {} 条 {} 的点赞 Set（Redis 数据丢失后的恢复动作）", count, bizType);
        return count;
    }

    /**
     * 点赞。返回 true 表示"本次真的点上了"。
     *
     * <p>{@code SADD} 的返回值就是判重结果：1 = 新加入（首次点赞），0 = 已在集合里（重复点赞）。
     * 这样一个命令同时完成"判断是否已赞"和"写入"，<b>没有「先查后写」的并发窗口</b> ——
     * 两个并发请求里只有一个能拿到 1。</p>
     */
    private boolean like(LikeRecordFormDTO dto) {
        Long userId = UserContext.getUser();
        String key = RemarkConstants.likesSetKey(dto.getBizType(), dto.getBizId());
        Long added = redisTemplate.opsForSet().add(key, userId.toString());
        if (added == null || added == 0) {
            return false;
        }
        try {
            save(new LikedRecord()
                    .setUserId(userId)
                    .setBizId(dto.getBizId())
                    .setBizType(dto.getBizType()));
        } catch (DuplicateKeyException e) {
            // 唯一索引挡住 = Redis 说"首次点赞"但 DB 已有行 → 两边不一致（Set 被清理过/回滚未生效）
            // 静默即可（业务上无害），但要留下探针日志
            log.warn("点赞明细已存在，Redis 与 DB 不一致：userId={}, bizType={}, bizId={}",
                    userId, dto.getBizType(), dto.getBizId());
        } catch (Exception e) {
            // 落库失败要补偿：撤掉刚才的 SADD。否则 Redis 认为"已点赞"而 DB 没有，
            // 用户再点一次会被判重挡掉 —— 点了个寂寞
            redisTemplate.opsForSet().remove(key, userId.toString());
            throw e;
        }
        return true;
    }

    /**
     * 取消点赞。返回 true 表示"本次真的取消了"。
     */
    private boolean unlike(LikeRecordFormDTO dto) {
        Long userId = UserContext.getUser();
        String key = RemarkConstants.likesSetKey(dto.getBizType(), dto.getBizId());
        Long removed = redisTemplate.opsForSet().remove(key, userId.toString());
        if (removed == null || removed == 0) {
            return false;
        }
        remove(new LambdaQueryWrapper<LikedRecord>()
                .eq(LikedRecord::getUserId, userId)
                .eq(LikedRecord::getBizType, dto.getBizType())
                .eq(LikedRecord::getBizId, dto.getBizId()));
        return true;
    }

    /** 标记该业务id 的点赞数需要同步 */
    private void markDirty(String bizType, Long bizId) {
        redisTemplate.opsForSet().add(RemarkConstants.likesDirtyKey(bizType), bizId.toString());
    }
}
