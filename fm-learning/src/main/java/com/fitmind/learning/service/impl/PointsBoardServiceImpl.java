package com.fitmind.learning.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.api.client.user.UserClient;
import com.fitmind.api.dto.user.UserDTO;
import com.fitmind.common.domain.query.PageQuery;
import com.fitmind.common.utils.UserContext;
import com.fitmind.learning.constants.LearningConstants;
import com.fitmind.learning.constants.RedisConstants;
import com.fitmind.learning.domain.po.PointsBoard;
import com.fitmind.learning.domain.po.PointsBoardSeason;
import com.fitmind.learning.domain.query.PointsBoardQuery;
import com.fitmind.learning.domain.vo.PointsBoardItemVO;
import com.fitmind.learning.domain.vo.PointsBoardVO;
import com.fitmind.learning.mapper.PointsBoardMapper;
import com.fitmind.learning.mapper.PointsRecordMapper;
import com.fitmind.learning.service.IPointsBoardSeasonService;
import com.fitmind.learning.service.IPointsBoardService;
import com.fitmind.learning.utils.TableInfoContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final PointsRecordMapper pointsRecordMapper;

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
            if (rows.isEmpty()) {
                // ★ 分表不存在（该赛季没跑过月度结算）→ 用积分明细按月实时重算（#C42）。
                //   顺序很重要：只有分表**确实没数据**时才兜底，已结算的赛季仍以快照为准。
                rows = queryHistoryFromLedger(query, vo);
            } else {
                fillMyBoardFromDb(season, vo);
            }
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
        TableInfoContext.setInfo(LearningConstants.POINTS_BOARD_TABLE_PREFIX + season);//动态表名
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

    /**
     * 历史赛季榜单页（查分表 {@code points_board_{season}}，动态表名生效）。
     *
     * <p>⚠️ 查不到时返回空列表，但这有两种<b>完全不同</b>的原因：
     * ① 分表不存在（该赛季没跑过月度结算）；② 该赛季确实没人得分。
     * 所以这里失败时只回空、<b>不返回"已结算"的信号</b>，由 {@link #queryBoard} 接着走流水兜底。</p>
     */
    private List<PointsBoard> queryHistory(PointsBoardQuery query) {
        TableInfoContext.setInfo(LearningConstants.POINTS_BOARD_TABLE_PREFIX + query.getSeason());
        try {
            Page<PointsBoard> page = page(query.toMpPage());
            for (PointsBoard row : page.getRecords()) {
                row.setRank(row.getId() == null ? null : row.getId().intValue());
            }
            return page.getRecords();
        } catch (Exception e) {
            // ★ 以前这里只 warn 一句"查询失败"，页面上的表现与"真的没人得分"完全一样 →
            //   排查方向被抹平（#C42）。现在把**下一步该干什么**也写进日志里。
            log.warn("历史赛季分表不可用（赛季 {}，多为该赛季未结算 —— 需补跑 createTableJob / savePointsBoard2DB）：{}",
                    query.getSeason(), e.getMessage());
            return List.of();
        } finally {
            TableInfoContext.remove();
        }
    }

    /**
     * 历史赛季的<b>跨月兜底</b>：分表不存在时，直接用积分明细（{@code points_record}）实时重算。
     *
     * <p><b>为什么需要它</b>：历史赛季的数据源是"月度结算快照"，而结算靠 XXL-JOB
     * （每月 1 日建表 + 落库）。这几个任务的 cron 全在<b>凌晨</b>，开发机那时通常不在线 ——
     * 实测 {@code xxl_job_log} 里 46/47 两条记录都没有、{@code ensureSeasonJob} 唯一一次触发
     * 也是「调度失败：执行器地址为空」。任务没跑成 → 分表不存在 → 历史赛季永久空白。
     * 把"历史"完全建立在一条<b>依赖任务执行过</b>的路径上，太脆。</p>
     *
     * <p><b>为什么以 points_record 为准、而不是 Redis 实时榜</b>（#C42 实测）：
     * 9 月的 ZSet 是 121 分、积分明细是 130 分，差 9 分。ZSet 是<b>派生数据</b>
     * （90 天 TTL、月初会被 {@code clearRedis} 删掉、Redis 重启可能丢），
     * {@code points_record} 才是"记录过什么"的流水：能重算任意月份，
     * 且与「今日积分明细」同源，口径不会自相矛盾。</p>
     *
     * <p>★ <b>这里刻意不做"合并实时榜"</b>：曾经为了让"明细被验收脚本删掉的人"也出现在榜上，
     * 这里会把 ZSet 里独有的人补进来 —— 后来去掉了。因为那是<b>治脏数据的下策</b>：
     * 脏数据（#C43）应该<b>直接把分表补对</b>，而不是让<b>每一次历史查询</b>都去兼容它。
     * 查询口径的特例越少越好。</p>
     *
     * @return 该页的榜单行（已带 rank）；顺带把"我的积分/排名"写进 vo
     */
    private List<PointsBoard> queryHistoryFromLedger(PointsBoardQuery query, PointsBoardVO vo) {
        if (query.getSeason() == null) {
            return List.of();
        }
        PointsBoardSeason season = seasonService.getById(query.getSeason().intValue());
        if (season == null || season.getBeginTime() == null || season.getEndTime() == null) {
            log.warn("历史赛季兜底跳过：赛季不存在，season={}", query.getSeason());
            return List.of();
        }
        LocalDateTime begin = season.getBeginTime().atStartOfDay();
        // 结束日当天 23:59:59 也算赛季内 → 上界取「结束日 + 1 天」的 0 点（左闭右开）
        LocalDateTime end = season.getEndTime().plusDays(1).atStartOfDay();

        List<PointsBoard> all = pointsRecordMapper.sumPointsGroupByUser(begin, end);
        // SQL 已按「分数倒序 + user_id 兜底」排好，名次按顺序赋即可
        for (int i = 0; i < all.size(); i++) {
            all.get(i).setRank(i + 1);
        }
        log.info("历史赛季分表不可用，已用积分明细兜底重算：season={}, 区间=[{}, {}), 上榜人数={}",
                query.getSeason(), begin, end, all.size());

        // 我的积分/排名：与列表同一份数据，不会自相矛盾
        Long myId = UserContext.getUser();
        if (myId != null) {
            all.stream()
                    .filter(r -> myId.equals(r.getUserId()))
                    .findFirst()
                    .ifPresent(mine -> {
                        vo.setPoints(mine.getPoints());
                        vo.setRank(mine.getRank());
                    });
        }
        return pageInMemory(all, query);
    }

    /**
     * 内存分页。
     *
     * <p>兜底路径的结果集是"一个月的活跃用户数"，量级很小（本项目演示数据只有个位数），
     * 所以直接把整月聚合结果拿来切页，比再写一条带窗口函数的 SQL 划算得多。
     * ★ 如果将来要支撑大流量，这里应换成带 {@code ROW_NUMBER() OVER (ORDER BY SUM(points) DESC)}
     * 的数据库分页 —— 现在是<b>已知的规模假设</b>，不是疏漏。</p>
     */
    private List<PointsBoard> pageInMemory(List<PointsBoard> all, PointsBoardQuery query) {
        int pageNo = query.getPageNo() == null ? PageQuery.DEFAULT_PAGE_NUM : query.getPageNo();
        int pageSize = query.getPageSize() == null ? PageQuery.DEFAULT_PAGE_SIZE : query.getPageSize();
        int from = Math.max(0, (pageNo - 1) * pageSize);
        if (from >= all.size()) {
            return List.of();
        }
        return all.subList(from, Math.min(all.size(), from + pageSize));
    }

    private String boardKey(LocalDate date) {
        return RedisConstants.POINTS_BOARD_KEY_PREFIX + date.format(BOARD_KEY_SUFFIX);
    }
}
