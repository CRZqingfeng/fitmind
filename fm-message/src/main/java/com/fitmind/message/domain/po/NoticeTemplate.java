package com.fitmind.message.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 通知模板。
 *
 * <p><b>★ 模板的意义在于"文案与代码解耦"</b>：运营想改"购课成功"的文案，
 * 不需要发版。业务代码只说"发一条 {@code purchase-success} 的通知，
 * 参数是课程名和金额"，长什么样由模板决定。</p>
 *
 * <p>{@code content} 里可以写 {@code {courseName}}、{@code {amount}} 这样的占位符，
 * 发送时用参数替换（见 {@code NoticeServiceImpl#render}）。</p>
 *
 * <p>业务代码<b>按 {@code code} 找模板而不是按 id</b>：id 是自增的，
 * 各环境（开发 / 测试 / 生产）的数字不一样，用 id 会写死环境。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("notice_template")
public class NoticeTemplate implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 模板名称（给人看的） */
    private String name;

    /** ★ 模板代号（给程序用的，如 purchase-success） */
    private String code;

    /** 通知类型，见 {@code MessageConstants.TYPE_*} */
    private Integer type;

    /** 模板状态：0-草稿 1-使用中 2-停用 */
    private Integer status;

    /** 通知标题（支持 {占位符}） */
    private String title;

    /** 通知内容（支持 {占位符}） */
    private String content;

    /**
     * 是否同时发短信：0-否 1-是。
     *
     * <p>⚠️ 显式写 {@code @TableField}：字段名以 {@code is} 开头时，
     * MyBatis-Plus 的驼峰转换在部分版本上会推出 {@code is_sms_template}
     * 之外的结果，显式指定最稳，也顺便告诉读代码的人列名叫什么。</p>
     */
    @TableField("is_sms_template")
    private Integer isSmsTemplate;

    private Long creater;

    private Long updater;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
