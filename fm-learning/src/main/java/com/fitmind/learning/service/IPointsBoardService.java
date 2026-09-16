package com.fitmind.learning.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.learning.domain.po.PointsBoard;
import com.fitmind.learning.domain.query.PointsBoardQuery;
import com.fitmind.learning.domain.vo.PointsBoardVO;

import java.time.LocalDate;

/**
 * 积分榜服务。
 *
 * <p>双写结构：当前赛季读 Redis ZSet（实时、O(logN) 排名），
 * 历史赛季读 MySQL 分表（冷却数据）。</p>
 */
public interface IPointsBoardService extends IService<PointsBoard> {

    /** 查询榜单（season 为空/0 → 当前赛季走 Redis；否则查对应赛季分表） */
    PointsBoardVO queryBoard(PointsBoardQuery query);

    /** 【结算用】给指定赛季建分表 */
    void createTable(Integer season);

    /**
     * 【结算用】把 Redis 里的赛季榜落到分表（支持分片）。
     *
     * @param season     赛季id
     * @param shardIndex 分片序号（0 开始）
     * @param shardTotal 分片总数
     * @return 落库条数
     */
    int saveToDb(Integer season, int shardIndex, int shardTotal);

    /** 【结算用】清理 Redis 中的赛季榜 */
    void clearRedis(Integer season);

    /** 赛季所属月份的 Redis key 后缀（yyyyMM） */
    LocalDate seasonBeginDate(Integer season);
}
