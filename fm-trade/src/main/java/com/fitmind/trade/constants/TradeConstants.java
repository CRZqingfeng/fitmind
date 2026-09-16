package com.fitmind.trade.constants;

/**
 * 交易模块常量：Redis key、明细状态。
 *
 * <p>散落在代码里的魔法值是排查问题的天敌，全部收在这里。</p>
 */
public interface TradeConstants {

    // ==================== Redis key ====================

    /**
     * 订单超时延迟队列（ZSet）。
     *
     * <p>member = orderId，score = <b>到期时间戳（毫秒）</b>。
     * 下单时 ZADD，支付成功时 ZREM，定时任务 ZRANGEBYSCORE 取到期的。</p>
     *
     * <p>命名与 learning 的 {@code learning:record:delay} 保持一致 ——
     * 同一个项目里同类机制用同一套命名，看到名字就知道是什么。</p>
     */
    String ORDER_DELAY_KEY = "trade:order:delay";

    /** 下单防重锁前缀，实际 key = {@code trade:order:lock:{userId}} */
    String ORDER_LOCK_PREFIX = "trade:order:lock:";

    /** 下单锁的持有秒数：只防"手抖点两下"，不需要长 */
    long ORDER_LOCK_SECONDS = 5;

    // ==================== 跨服务契约里的约定值 ====================

    /**
     * 课程「已上架」状态值。
     *
     * <p>⚠️ 与 fm-course 的 {@code CourseStatus.SHELF} 保持一致，但<b>故意不去 import 它</b>：
     * 那会让 fm-trade 依赖 fm-course 的<b>实现模块</b>，而同层业务服务之间只应该
     * 通过 fm-api 的契约通信（fm-trade 的 pom 里也确实没有 fm-course 依赖）。</p>
     *
     * <p>这个数字属于契约的一部分 —— {@code CourseSimpleDTO} 的字段注释里就写着
     * 「状态：2-已上架 3-下架 4-已完结」。在这里给它一个名字，比在业务代码里
     * 裸写 {@code status != 2} 强。</p>
     */
    int COURSE_STATUS_SHELF = 2;

    // ==================== 订单明细状态 ====================
    // detail 的 status 与父单同步冗余（这样"我买过这门课吗"只查一张表）

    /** 明细：待支付 */
    int DETAIL_NO_PAY = 1;
    /** 明细：已支付 */
    int DETAIL_PAYED = 2;
    /**
     * 明细：已关闭（父单被取消或超时）。
     *
     * <p>⚠️ 这个状态不是可有可无的：下单前的重复购买校验会查
     * "这门课有没有<b>待支付</b>的明细"。如果关闭订单时不同步明细，
     * 用户取消一笔订单后就再也买不了这门课了 —— 系统会一直告诉他
     * "有一笔待支付的订单请先处理"，而那笔订单其实已经关闭。
     * （验收脚本抓到的真 bug）</p>
     */
    int DETAIL_CLOSED = 3;
    /**
     * 明细：退款中（M4-4）。
     *
     * <p>★ <b>为什么从 4 开始而不是从 3</b>：3 已经被"已关闭"占用了。
     * 退款相关状态接在它后面，避免为了"好看"去改动已有值 ——
     * 那些值已经落在数据库里了，改一个数字就要写数据迁移脚本。</p>
     */
    int DETAIL_REFUNDING = 4;
    /** 明细：已退款（M4-4，终态） */
    int DETAIL_REFUNDED = 5;

    // ==================== 退款 ====================
    /**
     * Redis key：退款申请的下单防重锁前缀。
     *
     * <p>与 {@link #ORDER_LOCK_PREFIX} 同一套思路：退款申请也要防"手抖点两下"。
     * 但<b>真正的并发保障不靠这把锁</b>（锁是尽力而为的），
     * 而是靠 {@code refund_apply} 上的 {@code uk_active_detail} 唯一索引 ——
     * 锁负责让重复请求快速返回，索引负责保证数据不会重复。</p>
     */
    String REFUND_LOCK_PREFIX = "trade:refund:lock:";
    /** 学员对同一门课最多申请几次退款（业务规则；管理员不受限） */
    int STUDENT_MAX_REFUND_TIMES = 2;
}
