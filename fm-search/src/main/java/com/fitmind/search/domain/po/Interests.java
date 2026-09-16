package com.fitmind.search.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户兴趣。
 *
 * <p><b>id 就是 user_id</b>（一人一条），沿用常见实现设计——
 * 因为访问模式只有一种：「按用户查他的兴趣」，用用户 id 当主键
 * 就不必再建一次索引，查询也最快。</p>
 *
 * <p>{@code interests} 用逗号分隔的分类 id（多为二级分类 = 训练部位）。
 * 没有拆成关联表是因为不需要反查「哪些人对某分类感兴趣」，
 * 单列存储要简单得多。</p>
 */
@Data
@Accessors(chain = true)
@TableName("interests")
@Schema(description = "用户兴趣")
public class Interests implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户id，即主键 */
    @TableId(value = "id", type = IdType.INPUT)
    @Schema(description = "用户id")
    private Long id;

    /** 关注的分类id，逗号分隔 */
    @Schema(description = "关注的分类id，逗号分隔")
    private String interests;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
