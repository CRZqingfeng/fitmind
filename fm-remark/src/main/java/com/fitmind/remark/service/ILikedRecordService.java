package com.fitmind.remark.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.remark.domain.dto.LikeRecordFormDTO;
import com.fitmind.remark.domain.po.LikedRecord;

import java.util.List;
import java.util.Set;

/**
 * 点赞服务。
 */
public interface ILikedRecordService extends IService<LikedRecord> {

    /**
     * 点赞 / 取消点赞。
     *
     * <p>只有"真实变更"（首次点赞 / 真的取消）才会标脏等待同步；
     * 重复点赞、重复取消都是幂等空操作。</p>
     */
    void addLikeRecord(LikeRecordFormDTO dto);

    /**
     * 批量查询当前用户的点赞状态。
     *
     * @param bizType 业务类型（Redis key 的一部分）
     * @param bizIds  业务id 集合
     * @return 已点赞的业务id 子集
     */
    Set<Long> isBizLiked(String bizType, List<Long> bizIds);

    /**
     * 把一批脏标对应的最新点赞数发给业务方（XXL-JOB 调用）。
     *
     * @param bizType   业务类型
     * @param batchSize 单批条数
     * @return 本次处理条数（0 表示没有积压）
     */
    int syncLikedTimes(String bizType, int batchSize);

    /**
     * 【故障恢复】从 DB 明细重算并重建 Redis Set。
     *
     * <p>Redis 数据丢失（重启未持久化、误删、Set 被清理）时用：
     * 不重建的话 {@code SCARD} 会归零，同步任务会把业务表的 {@code liked_times}
     * 写成 0 —— 那是数据损坏而不是"丢点赞"。</p>
     *
     * @param bizType 业务类型
     * @param bizIds  要重建的业务id 集合
     * @return 处理的条数
     */
    int rebuild(String bizType, List<Long> bizIds);
}
