package com.fitmind.course.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 报名人数变动日志 Mapper（M7-3）。
 *
 * <p>这张表只为一件事存在：让 {@code order.pay} / {@code order.refund} 的消费
 * <b>幂等</b>。RabbitMQ 是"至少一次"投递，消费者重启、ack 丢失、网络抖动都会
 * 让同一条消息再来一次，而 {@code sold = sold + 1} 重复执行就是实打实的多算。</p>
 *
 * <p>为什么不用 Redis SETNX 做幂等（项目里别的模块是那么做的）：
 * 计数变动与"记录变动发生过"必须是<b>同一个事务</b>。Redis 不参与数据库事务，
 * 一旦"SETNX 成功但 DB 更新失败"，这条消息就永久丢了（下次重投会被 Redis 挡住）。
 * 用同库的表，两者要么都成功、要么都回滚。</p>
 */
@Mapper
public interface CourseSoldLogMapper {

    /**
     * 登记一次变动；若该 (订单, 课程, 变动方向) 已登记过则什么都不做。
     *
     * <p>★ 用 {@code ON DUPLICATE KEY UPDATE id = id} 而不是 {@code INSERT IGNORE}：
     * 前者只吞掉<b>唯一键冲突</b>这一种情况，其余错误（列名写错、字段超长）照常抛出；
     * 后者会把一切错误都变成"静默跳过"，等于给整条链路盖了块布。</p>
     *
     * @return 1 = 本次新登记（需要更新 sold）；0 = 之前已登记过（幂等跳过）
     */
    @Insert("INSERT INTO course_sold_log (order_id, course_id, delta) "
            + "VALUES (#{orderId}, #{courseId}, #{delta}) "
            + "ON DUPLICATE KEY UPDATE id = id")
    int insertIfAbsent(@Param("orderId") Long orderId,
                       @Param("courseId") Long courseId,
                       @Param("delta") int delta);
}
