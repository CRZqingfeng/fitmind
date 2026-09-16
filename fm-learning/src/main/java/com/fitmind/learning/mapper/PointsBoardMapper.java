package com.fitmind.learning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.learning.domain.po.PointsBoard;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 积分榜 Mapper。
 */
@Mapper
public interface PointsBoardMapper extends BaseMapper<PointsBoard> {

    /**
     * 动态创建赛季分表。
     *
     * <p>DDL 写在 XML 里（{@code resources/mapper/PointsBoardMapper.xml}）——
     * 表结构跟着代码走，环境可重建，与 MinIO bucket / ES 索引 / IK 词典同一原则。</p>
     *
     * @param tableName 物理表名，如 points_board_2
     */
    void createPointsBoardTable(@Param("tableName") String tableName);
}
