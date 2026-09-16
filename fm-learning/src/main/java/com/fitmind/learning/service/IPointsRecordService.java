package com.fitmind.learning.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.learning.domain.po.PointsRecord;
import com.fitmind.learning.domain.vo.PointsStatisticsVO;
import com.fitmind.learning.enums.PointsRecordType;

import java.util.List;

/**
 * 积分服务。
 */
public interface IPointsRecordService extends IService<PointsRecord> {

    /**
     * 加积分（签到 / 学习 / 问答 / 笔记统一入口）。
     *
     * <p>三件事一起做：每日上限校验（含补差额）→ 落明细（唯一索引保证幂等）→ 同步实时榜。</p>
     *
     * @param userId 用户id
     * @param points 本次应得积分
     * @param type   积分类型（自带每日上限）
     * @param bizId  业务唯一标识（幂等用），如 {@code 12:345}、{@code 20260914}
     */
    void addPointsRecord(Long userId, int points, PointsRecordType type, String bizId);

    /** 查询今日积分明细（按类型汇总） */
    List<PointsStatisticsVO> queryMyPointsToday();
}
