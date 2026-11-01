package com.fitmind.plan.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 动作库（内容型）。
 *
 * <p><b>最容易写错的地方</b>：{@code category / bodyPart / equipment} 必须是**列**并且建索引，
 * 不能塞进 JSON。左侧栏和器械 chips 是最高频的筛选路径，塞 JSON 之后只能全表扫 +
 * 应用层过滤，876 条时无感，P3 全量之后就是每次点分类都慢半秒。</p>
 *
 * <p>{@code heat / lumbarPressure} 在开源数据集里没有 → 允许为 null，
 * 前端显示"—"。<b>不要为了"看着完整"填 0</b>：0 会被读成"这个动作不伤腰"，是安全问题。</p>
 */
@Data
@Accessors(chain = true)
@TableName("exercise")
public class Exercise implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String name;

    /** 英文名（由数据集 id 还原，非权威译名） */
    private String enName;

    /** 热身 / 拉伸 / 有氧 / 力量 */
    private String category;

    /** 胸/背/腿/肩/二头/三头/小腿/前臂/臀部/腹部/斜方肌/其他 */
    private String bodyPart;

    /** 徒手/哑铃/杠铃/史密斯/悍马机/固定器械/弹力带/其他 */
    private String equipment;

    /** 难度 1~5 */
    private Integer difficulty;

    /** 热量参考：数据集无 → null */
    private Integer heat;

    /** 腰椎压力 1~5：数据集无 → null */
    private Integer lumbarPressure;

    private String imageUrl;

    private String gifUrl;

    /** 主要肌群 JSON 数组（原样存数据集值） */
    private String primaryMusclesJson;

    /** 次要肌群 JSON 数组 */
    private String secondaryMusclesJson;

    /** 动作步骤 JSON 数组 */
    private String stepsJson;

    /** 提示：数据集无此字段 → null */
    private String tips;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
