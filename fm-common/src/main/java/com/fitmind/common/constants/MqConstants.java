package com.fitmind.common.constants;

/**
 * 消息队列常量。
 *
 * <p>集中放在公共模块，生产方（fm-course）和消费方（fm-search）共用同一份定义，
 * 避免两边各写一套字符串、改了一处漏了另一处。</p>
 *
 * <p>命名约定：交换机用 {@code xxx.topic}，路由键用 {@code xxx.yyy}。</p>
 */
public interface MqConstants {

    /** 交换机 */
    interface Exchange {

        /** 课程相关（上架 / 下架 / 过期 / 删除） */
        String COURSE_EXCHANGE = "course.topic";

        /** 学习相关（M3 使用） */
        String LEARNING_EXCHANGE = "learning.topic";

        /** 体测 / 评估相关（M5：体测完成事件，fm-exam 生产 → fm-data 消费） */
        String EXAM_EXCHANGE = "exam.topic";
        /** 支付相关（M4-2：pay 生产，trade 消费） */
        String PAY_EXCHANGE = "pay.topic";

        /** 点赞相关（M3-3，fm-remark 生产、业务方消费） */
        String LIKE_EXCHANGE = "like.record.topic";
        /**
         * AI 模块的事件交换机（M6-6）。
         *
         * <p>★ 每个业务域一个 exchange（course / learning / exam / pay / ai），
         * 而不是所有模块挤在一个：消费者绑定时意图一目了然，
         * 以后做权限或配额隔离也有边界可依。</p>
         */
        String AI_EXCHANGE = "ai.topic";
    }

    /** 路由键 */
    interface Key {

        /** 课程上架 */
        String COURSE_UP_KEY = "course.up";

        /** 课程下架 */
        String COURSE_DOWN_KEY = "course.down";

        /** 课程过期 */
        String COURSE_EXPIRE_KEY = "course.expire";

        /**
         * 课程删除。
         *
         * <p>⚠️ <b>当前未使用</b>：删除课程对搜索侧的后果与下架完全一致（都是把文档
         * 从索引里移除），所以 {@code CourseDraftServiceImpl#delete} 直接复用了
         * {@link #COURSE_DOWN_KEY}，没有为它单独建队列与消费者。
         * 保留这个常量是为了语义完整（将来若需要"删除"与"下架"在搜索侧有区别行为时启用）。</p>
         */
        String COURSE_DELETE_KEY = "course.delete";

        /** 完成训练动作（学习积分事件；常见实现只有消费者没有生产者，M3 接通） */
        String SECTION_LEARNED = "section.learned";

        /** 每日签到（积分事件，M3-2） */
        String SIGN_IN = "sign.in";

        /** 发表问答回复（积分事件，M3-3） */
        String WRITE_REPLY = "reply.new";

        /** 发表笔记（积分事件，M3-3） */
        String WRITE_NOTE = "note.new";

        /** 笔记被收藏（积分事件，M3-3） */
        String NOTE_GATHERED = "note.gathered";

        /**
         * 答对动作小测（积分事件，M2-2c）。
         *
         * <p>生产方是 fm-course（判分在课程侧完成），消费方是 fm-learning。
         * 幂等 bizId 形如 {@code courseId:cataId:subjectId}——<b>按题</b>而不是按节点，
         * 这样「一个动作 3 道题、答对 2 道」能拿到 2 份分，
         * 而同一道题重复答对则被唯一索引挡住。</p>
         */
        String PRACTICE_ANSWERED = "practice.answered";

        /**
         * 支付成功（M4-2）。
         *
         * <p>生产方 fm-pay（回调验签通过后），消费方 fm-trade。
         * 幂等不靠消息本身 —— 消费侧用订单的<b>条件更新</b>兜住
         * （{@code WHERE status = 待支付}，重复投递第二次会更新 0 行）。</p>
         */
        String PAY_SUCCESS_KEY = "pay.success";

        /**
         * 订单已支付（M4-2）。
         *
         * <p>生产方 fm-trade（订单状态改成已支付之后），消费方 fm-learning。
         * 消费端写课表，幂等由 {@code learning_lesson} 的
         * {@code (user_id, course_id)} 唯一索引保证（M3-1 就建好了）。</p>
         */
        String ORDER_PAY_KEY = "order.pay";

        // ==================== 退款（M4-4） ====================

        /**
         * 退款结果（M4-4）。
         *
         * <p>生产方 fm-pay（渠道退款回调 / 补偿任务查得结果之后），消费方 fm-trade。
         * 之所以<b>不让交易侧同步等结果</b>：真实渠道的退款是异步的 ——
         * 微信只回"已受理"，支付宝可能当场成功。结果语义不统一，
         * 所以统一以"回调 / MQ"为准。</p>
         *
         * <p>幂等由消费侧的 {@code refund_apply} 状态条件更新保证。</p>
         */
        String REFUND_RESULT_KEY = "refund.result";

        /**
         * 订单退款成功（M4-4）。
         *
         * <p>生产方 fm-trade（退款确实到账之后），消费方有<b>两个</b>：</p>
         * <ul>
         *   <li>fm-learning：撤销课表（钱退了，权益不该留）</li>
         *   <li>fm-message：发"退款到账"站内信</li>
         * </ul>
         *
         * <p>★ <b>一个 routing key 绑多个队列</b>，这是 topic 交换机的标准用法，
         * 也是它的价值所在：将来要加第三个消费者（比如 fm-data 统计退款率），
         * <b>生产方一行都不用改</b>。如果当初写成同步 Feign 调用，
         * 每加一个下游就要改一次交易服务。</p>
         */
        String ORDER_REFUND_KEY = "order.refund";

        /**
         * 体测完成（M5）。
         *
         * <p>生产方 fm-exam，消费方 fm-data（统计"今日新增体测"）。
         * 消费侧必须自己做幂等（重复投递不能重复计数）。</p>
         */
        String ASSESSMENT_CREATED_KEY = "assessment.created";

        // ==================== AI 对话（M6-6） ====================
        /**
         * 一轮 AI 对话完成（携带 token / 首字延迟 / 工具次数等指标）。
         *
         * <p>★ 与别的 key 不同，这条消息带的不是"一次行为"而是<b>一组量</b>
         * （token 数、毫秒数），但消费端的处理方式完全一样：
         * 去重 → 累加 → 定时汇总。<b>指标型事件不需要新的采集通道，只需要新的 key。</b></p>
         */
        String AI_CHAT = "ai.chat";

        // ==================== 点赞数变更（M3-3） ====================

        /**
         * 点赞数变更路由键模板：{@code {bizType}.times.changed}。
         *
         * <p>生产方用 {@code String.format(LIKE_TIMES_KEY_TEMPLATE, bizType)} 拼出具体 key；
         * 消费方在 {@code @RabbitListener} 里请用下面两个显式常量（注解需要编译期常量）。</p>
         */
        String LIKE_TIMES_KEY_TEMPLATE = "%s.times.changed";

        /** 问答回答的点赞数变更（fm-learning 消费） */
        String QA_LIKED_TIMES_KEY = "QA.times.changed";

        /**
         * 笔记的点赞数变更（fm-learning 消费）。
         *
         * <p>⚠️ 常见实现定义了同名的 key 却<b>没有任何消费者</b>，且 note 表也没有 liked_times
         * 字段——整条链路静默不存在（编译、启动都不报错）。本阶段接通。</p>
         */
        String NOTE_LIKED_TIMES_KEY = "NOTE.times.changed";
    }

    /**
     * 点赞业务类型（bizType）。
     *
     * <p>它同时扮演两个角色：① MQ 路由键的前缀；② <b>Redis key 的一部分</b>
     * （{@code like:set:{bizType}:{bizId}}）。</p>
     *
     * <p>⚠️ 为什么 Redis key 要带上 bizType：常见实现只用 {@code bizId} 做 key，
     * 那是建立在「雪花 id 全局唯一」的前提上。我们的主键是自增的，
     * 回答 id=5 与笔记 id=5 会撞进同一个 key，所以必须用类型隔离。</p>
     */
    interface BizType {

        /** 问答回答 / 评论 */
        String QA = "QA";

        /** 笔记 */
        String NOTE = "NOTE";
    }

    /** 队列 */
    interface Queue {

        /** 课程上架队列（fm-search 消费，同步 ES 索引） */
        String COURSE_UP_QUEUE = "course.up.queue";

        /** 课程下架队列（fm-search 消费，删除 ES 索引） */
        String COURSE_DOWN_QUEUE = "course.down.queue";

        /** 课程过期队列（M3 课程过期时用，同样由 fm-search 消费） */
        String COURSE_EXPIRE_QUEUE = "course.expire.queue";

        /** 学习积分队列（fm-learning 消费，完成训练动作 +10） */
        String LEARNING_POINTS_QUEUE = "learning.points.queue";

        /** 签到积分队列（fm-learning 消费） */
        String SIGN_POINTS_QUEUE = "sign.points.queue";

        /** 问答积分队列（fm-learning 消费） */
        String QA_POINTS_QUEUE = "qa.points.queue";

        /** 笔记积分队列（fm-learning 消费） */
        String NOTE_POINTS_QUEUE = "note.points.queue";

        /** 笔记被收藏的积分队列（与写笔记分开：routing key 不同，语义也不同） */
        String NOTE_GATHERED_POINTS_QUEUE = "note.gathered.points.queue";

        /** 问答回答点赞数变更队列（fm-learning 消费） */
        String QA_LIKED_TIMES_QUEUE = "qa.liked.times.queue";

        /** 笔记点赞数变更队列（fm-learning 消费） */
        String NOTE_LIKED_TIMES_QUEUE = "note.liked.times.queue";

        /** 小测积分队列（fm-learning 消费，答对一题 +2） */
        String PRACTICE_POINTS_QUEUE = "practice.points.queue";

        /** 支付成功队列（fm-trade 消费） */
        String PAY_SUCCESS_QUEUE = "pay.success.queue";

        /** 订单已支付队列（fm-learning 消费，写课表） */
        String ORDER_PAY_QUEUE = "order.pay.queue";

        // ==================== 退款（M4-4） ====================

        /** 退款结果队列（fm-trade 消费，驱动退款申请的状态流转） */
        String REFUND_RESULT_QUEUE = "refund.result.queue";

        /**
         * 订单退款成功队列（fm-learning 消费，撤销课表）。
         *
         * <p>⚠️ 它与 {@link #REFUND_NOTICE_QUEUE} <b>绑同一个 routing key</b>
         * （{@code order.refund}）但队列名不同 —— 这是 topic 交换机的正常用法：
         * 一条业务事件、多个消费者各收各的，互不影响。</p>
         */
        String ORDER_REFUND_QUEUE = "order.refund.queue";

        /** 退款到账通知队列（fm-message 消费，发站内信） */
        String REFUND_NOTICE_QUEUE = "refund.notice.queue";

        /**
         * 订单已支付的通知队列（fm-message 消费，发"购课成功"站内信）。
         *
         * <p>⚠️ 与 {@link #ORDER_PAY_QUEUE}（fm-learning 消费，写课表）<b>绑同一个
         * routing key {@code order.pay}</b>，但队列不同。这是 topic 交换机的正常用法：
         * <b>一条业务事件、两个消费者各收各的</b>。</p>
         *
         * <p>为什么不直接让 fm-learning 写课表成功后去调消息服务：
         * 那样"发通知"就成了"写课表"的下游，<b>写课表失败就不发通知</b> ——
         * 而用户其实已经付钱了，收不到"购课成功"的通知会很困惑。
         * 拆成两个独立消费者，各自的成败互不影响。</p>
         */
        String ORDER_PAY_NOTICE_QUEUE = "order.pay.notice.queue";

        /** 体测完成队列（fm-exam 生产 → fm-data 消费，统计用） */
        String ASSESSMENT_QUEUE = "assessment.created.queue";
        // ==================== 数据中心（M5，全部由 fm-data 消费） ====================
        /**
         * 签到统计队列。
         *
         * <p>★ 与 {@link #SIGN_POINTS_QUEUE} 绑<b>同一个 routing key {@code sign.in}</b>。
         * 这正好印证了 M4-4"通知走 MQ"的价值：现在要加一个数据中心，
         * <b>生产方（fm-learning）一行都不用改</b>，加个队列绑上去就行。</p>
         */
        String DATA_SIGN_QUEUE = "data.sign.queue";
        /** 完成训练统计队列（routing key: section.learned） */
        String DATA_LEARN_QUEUE = "data.learn.queue";
        /** 小测答对统计队列（routing key: practice.answered） */
        String DATA_PRACTICE_QUEUE = "data.practice.queue";
        /** 成交统计队列（routing key: order.pay） */
        String DATA_ORDER_PAY_QUEUE = "data.order.pay.queue";

        /** 数据中心：AI 对话指标（M6-6）。★ 绑在 ai.chat 上，与其他消费者的队列互不影响 */
        String DATA_AI_CHAT_QUEUE = "data.ai.chat.queue";
        /** 退款统计队列（routing key: order.refund） */
        String DATA_ORDER_REFUND_QUEUE = "data.order.refund.queue";
        /** 体测统计队列（routing key: assessment.created） */
        String DATA_ASSESSMENT_QUEUE = "data.assessment.queue";
    }
}
