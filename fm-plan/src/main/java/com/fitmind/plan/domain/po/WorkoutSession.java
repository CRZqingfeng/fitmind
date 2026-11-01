package com.fitmind.plan.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 一次训练（日志）。
 *
 * <p><b>最容易写错的地方</b>：防重复打卡靠
 * {@code uk_user_day_plan(user_id, session_date, plan_day_id)} 这个唯一索引，
 * <b>不是</b>靠"先查今天有没有练过"。查完到插入之间就是并发窗口，
 * 用户手快连点两下「开始训练」，就会出现两条记录 —— 而首页的累计分钟数会凭空多一倍。</p>
 *
 * <p>{@code durationSec} 的口径：{@code max(实际耗时, Σ已完成组估算秒)}。
 * 只取实际耗时的话，验收脚本秒级跑完会得到 0 秒，前端显示"训练了 0 分钟"。</p>
 */
@Data
@Accessors(chain = true)
@TableName("workout_session")
public class WorkoutSession implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long userPlanId;

    private Long planDayId;

    private LocalDate sessionDate;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 时长秒数（完成训练时写） */
    private Integer durationSec;

    /** 消耗千卡；0 = 未估（P1 接体重/MET 后再算，不编造） */
    private Integer kcal;

    /** 总容量 = Σ(重量kg × 次数) */
    private BigDecimal totalVolume;

    /** 状态：0-进行中 1-已完成 2-已放弃 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
