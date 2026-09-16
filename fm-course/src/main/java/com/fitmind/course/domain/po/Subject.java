package com.fitmind.course.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 动作小测题目。
 *
 * <p>题目<b>挂在课程上</b>（courseId），不做跨课程题库——健身小测问的是
 * 「这个动作的要点」，脱离动作没有意义。</p>
 *
 * <p><b>★ 为什么 options 是一个 JSON 列</b>：常见实现用 {@code option1..option10}
 * 十个列，靠 {@code ReflectUtils.setFieldValue(subject, "option" + (count + 1), ...)}
 * 按列名拼字符串反射读写——改字段名编译器不知道，运行时才炸；十个列里也大部分是 NULL。
 * 我们用 {@link JacksonTypeHandler} 把 {@code List<String>} 序列化进一个 JSON 列，
 * 反射那套整个消失。</p>
 */
@Data
@Accessors(chain = true)
@TableName(value = "subject", autoResultMap = true)
public class Subject implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属课程id */
    private Long courseId;

    /** 题干 */
    private String name;

    /**
     * 题型：1-单选 2-多选 3-不定向 4-判断。
     *
     * <p>⚠️ 项目里其他状态字段（课表 status、积分 type）都存 Integer，
     * 这里保持一致，不做 MP 枚举映射。</p>
     */
    private Integer subjectType;

    /** 难易度：1-简单 2-中等 3-困难 */
    private Integer difficulty;

    /**
     * 选项数组（JSON 列）。
     *
     * <p>判断题不用选项，这里为 null；选择题 2~10 个。</p>
     */
    @TableField(value = "options", typeHandler = JacksonTypeHandler.class)
    private List<String> options;

    /** 正确答案：选项序号（从 1 开始），多个用逗号分隔；判断题 1-正确 2-错误 */
    private String answer;

    /** 答案解析（提交后才下发，拉题接口不返回） */
    private String analysis;

    /** 题目分值（小测计分用，与积分体系的"答对得分"是两回事） */
    private Integer score;

    /** 被作答次数 */
    private Integer answerTimes;

    /** 答对次数 */
    private Integer correctTimes;

    private Long creater;

    private Long updater;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 把逗号分隔的 {@code answer} 解析成选项序号列表。
     *
     * <p>方法名<b>故意不带 get 前缀</b>：带了 Jackson 会把它当成一个属性序列化出去，
     * 在 VO 里多出一个不该给前端的字段。</p>
     */
    public List<Integer> parseAnswers() {
        List<Integer> result = new ArrayList<>();
        if (answer == null || answer.isBlank()) {
            return result;
        }
        Arrays.stream(answer.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(s -> result.add(Integer.parseInt(s)));
        return result;
    }
}
