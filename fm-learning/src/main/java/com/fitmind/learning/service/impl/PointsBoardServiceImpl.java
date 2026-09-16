package com.fitmind.learning.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.api.client.user.UserClient;
import com.fitmind.api.dto.user.UserDTO;
import com.fitmind.common.utils.UserContext;
import com.fitmind.learning.constants.LearningConstants;
import com.fitmind.learning.constants.RedisConstants;
import com.fitmind.learning.domain.po.PointsBoard;
import com.fitmind.learning.domain.po.PointsBoardSeason;
import com.fitmind.learning.domain.query.PointsBoardQuery;
import com.fitmind.learning.domain.vo.PointsBoardItemVO;
import com.fitmind.learning.domain.vo.PointsBoardVO;
import com.fitmind.learning.mapper.PointsBoardMapper;
import com.fitmind.learning.service.IPointsBoardSeasonService;
import com.fitmind.learning.service.IPointsBoardService;
import com.fitmind.learning.utils.TableInfoContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 积分榜实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsBoardServiceImpl extends ServiceImpl<PointsBoardMapper, PointsBoard>
        implements IPointsBoardService {

    private static final DateTimeFormatter BOARD_KEY_SUFFIX = DateTimeFormatter.ofPattern("yyyyMM");

    /** 每次从 ZSet 取的条数上限（防止一次拉爆内存） */
    private static final int BATCH_SIZE = 10;

    private final StringRedisTemplate redisTemplate;
    private final IPointsBoardSeasonService seasonService;
    private final UserClient userClient;

    @Override
    public PointsBoardVO queryBoard(PointsBoardQuery query) {
        Long season = query.getSeason();
        boolean current = season == null || season == 0;

        PointsBoardVO vo = new PointsBoardVO();
        List<PointsBoard> rows;
        if (current) {
            String key = boardKey(LocalDate.now());
            fillMyBoardFromRedis(key, vo);
            rows = listFromRedis(key, query.getPageNo(), query.getPageSize());
        } else {
            rows = queryHistory(query);
            fillMyBoardFromDb(season, vo);
        }
        if (rows.isEmpty()) {
            vo.setBoardList(List.of());
            return vo;
        }
        // 批量补用户名（避免 N+1 次调用 fm-user）
        List<Long> userIds = rows.stream().map(PointsBoard::getUserId).filter(Objects::nonNull).distinct().toList();
        List<UserDTO> users = userClient.queryUserByIds(userIds);
        Map<Long, String> nameMap = users == null ? Map.of()
                : users.stream().filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserDTO::getId, UserDTO::getName, (a, b) -> a));

        List<PointsBoardItemVO> items = new ArrayList<>(rows.size());
        for (PointsBoard row : rows) {
            PointsBoardItemVO item = new PointsBoardItemVO();
            item.setRank(row.getRank());
            item.setPoints(row.getPoints());
            item.setName(nameMap.get(row.getUserId()));
            items.add(item);
        }
        vo.setBoardList(items);
        return vo;
    }

    @Override
    public void createTable(Integer season) {
        String tableName = LearningConstants.POINTS_BOARD_TABLE_PREFIX + season;
        baseMapper.createPointsBoardTable(tableName);
        log.info("积分榜分表已就绪：{}", tableName);
    }

    @Override
    public int saveToDb(Integer season, int shardIndex, int shardTotal) {
        LocalDate month = seasonBeginDate(season);
        String key = boardKey(month);
        TableInfoContext.setInfo(LearningConstants.POINTS_BOARD_TABLE_PREFIX + season);
        int total = 0;
        try {
            // ★ 分片分页：第 index 个分片从第 index+1 页开始，步长是分片总数
            //   单实例（total=1）时等价于顺序全量；3 个实例时各自处理 1/3 的页，互不重叠
            int pageNo = shardIndex + 1;
            while (true) {
                List<PointsBoard> rows = listFromRedis(key, pageNo, BATCH_SIZE);
                if (rows.isEmpty()) {
                    break;
                }
                // id 即排名：按排名顺序插入，自增 id 天然等于排名
                rows.forEach(r -> r.setId(r.getRank().longValue()));
                saveBatch(rows);
                total += rows.size();
                pageNo += shardTotal;
            }
        } finally {
            // ⚠️ 必须清理：线程池复用线程，残留会让后续普通查询也去查分表
            TableInfoContext.remove();
        }
        log.info("榜单落库完成：season={}, shard={}/{}, 条数={}", season, shardIndex, shardTotal, total);
        return total;
    }

    @Override
    public void clearRedis(Integer season) {
        redisTemplate.unlink(boardKey(seasonBeginDate(season)));
    }

    @Override
    public LocalDate seasonBeginDate(Integer season) {
        PointsBoardSeason entity = seasonService.getById(season);
        if (entity == null) {
            throw new com.fitmind.common.exceptions.BizIllegalException("赛季不存在：" + season);
        }
        return entity.getBeginTime();
    }

    // ==================== 内部方法 ====================

    /** 从 ZSet 取榜单页（含排名计算） */
    private List<PointsBoard> listFromRedis(String key, Integer pageNo, Integer pageSize) {
        int from = (pageNo - 1) * pageSize;
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .reverseRangeWithScores(key, from, from + pageSize - 1);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        List<PointsBoard> list = new ArrayList<>(tuples.size());
        int rank = from + 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() == null || tuple.getScore() == null) {
                continue;
            }
            list.add(new PointsBoard()
                    .setUserId(Long.valueOf(tuple.getValue()))
                    .setPoints(tuple.getScore().intValue())
                    .setRank(rank++));
        }
        return list;
    }

    /** 我的当前赛季积分与排名（Redis 直查） */
    private void fillMyBoardFromRedis(String key, PointsBoardVO vo) {
        Long userId = UserContext.getUser();
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            return;
        }
        vo.setPoints(score.intValue());
        Long rank = redisTemplate.opsForZSet().reverseRank(key, userId.toString());
        // ZREVRANK 是 0-based，展示要 +1
        vo.setRank(rank == null ? null : rank.intValue() + 1);
    }

    /** 我的历史赛季积分与排名（查分表） */
    private void fillMyBoardFromDb(Long season, PointsBoardVO vo) {
        Long userId = UserContext.getUser();
        TableInfoContext.setInfo(LearningConstants.POINTS_BOARD_TABLE_PREFIX + season);
        try {
            PointsBoard mine = lambdaQuery().eq(PointsBoard::getUserId, userId).one();
            if (mine != null) {
                vo.setPoints(mine.getPoints());
                // 分表里 id 就是排名
                vo.setRank(mine.getId() == null ? null : mine.getId().intValue());
            }
        } catch (Exception e) {
            log.warn("查询历史赛季榜失败（赛季 {}）：{}", season, e.getMessage());
        } finally {
            TableInfoContext.remove();
        }
    }

    /** 历史赛季榜单页（查分表，动态表名生效） */
    private List<PointsBoard> queryHistory(PointsBoardQuery query) {
        TableInfoContext.setInfo(LearningConstants.POINTS_BOARD_TABLE_PREFIX + query.getSeason());
        try {
            Page<PointsBoard> page = page(query.toMpPage());
            for (PointsBoard row : page.getRecords()) {
                row.setRank(row.getId() == null ? null : row.getId().intValue());
            }
            return page.getRecords();
        } catch (Exception e) {
            log.warn("查询历史赛季榜失败（赛季 {}）：{}", query.getSeason(), e.getMessage());
            return List.of();
        } finally {
            TableInfoContext.remove();
        }
    }

    private String boardKey(LocalDate date) {
        return RedisConstants.POINTS_BOARD_KEY_PREFIX + date.format(BOARD_KEY_SUFFIX);
    }
}
