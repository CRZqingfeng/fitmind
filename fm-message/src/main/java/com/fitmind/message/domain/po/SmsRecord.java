package com.fitmind.message.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 短信下发记录。
 *
 * <p><b>★ 为什么要有这张表（常见实现没有）</b>：短信是"发出去就收不回来"的动作。
 * 没有留痕，就无法回答这三个问题：</p>
 * <ul>
 *   <li>这条短信到底发出去了没有？（排查"用户说没收到"）</li>
 *   <li>同一个手机号被发了几条？（排查短信轰炸）</li>
 *   <li>验收脚本怎么证明短信链路是通的？（常见实现只能翻日志文件）</li>
 * </ul>
 *
 * <p>我们全程坚持"验收查中间件的真实数据"，短信也一样 ——
 * 脚本直查 {@code sms_record} 表，而不是 grep 日志。
 * 日志会被截断、会滚动、会在重启时被覆盖，表不会。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("sms_record")
public class SmsRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 接收用户id（可空：注册验证码的场景下用户还不存在） */
    private Long userId;

    private String phone;

    /** 短信模板代号 */
    private String templateCode;

    /** 实际下发的短信内容（占位符已渲染） */
    private String content;

    /** 下发结果：1-成功 2-失败 */
    private Integer status;

    /** 渠道返回描述 / 失败原因 */
    private String resultMsg;

    private LocalDateTime createTime;
}
