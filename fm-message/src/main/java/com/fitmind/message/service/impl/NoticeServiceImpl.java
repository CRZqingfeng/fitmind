package com.fitmind.message.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitmind.api.client.user.UserClient;
import com.fitmind.api.dto.user.UserDTO;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.domain.query.PageQuery;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.message.config.MessageProperties;
import com.fitmind.message.constants.MessageConstants;
import com.fitmind.message.domain.dto.NoticeTemplateFormDTO;
import com.fitmind.message.domain.po.NoticeTemplate;
import com.fitmind.message.domain.po.SmsRecord;
import com.fitmind.message.domain.po.UserInbox;
import com.fitmind.message.mapper.NoticeTemplateMapper;
import com.fitmind.message.mapper.SmsRecordMapper;
import com.fitmind.message.mapper.UserInboxMapper;
import com.fitmind.message.service.INoticeService;
import com.fitmind.message.thirdparty.ISmsHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 通知服务实现。
 *
 * <p><b>★ 本类的核心是"一次发送 = 三步 + 两层幂等"</b>：</p>
 * <pre>
 *   查模板（按 code，不按 id）→ 渲染 {占位符} → 写用户信箱（可选再发短信）
 *                                              ↑
 *                            uk_user_source 唯一索引在这里挡重复
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoticeServiceImpl implements INoticeService {

    private final NoticeTemplateMapper templateMapper;
    private final UserInboxMapper inboxMapper;
    private final SmsRecordMapper smsRecordMapper;
    private final ISmsHandler smsHandler;
    private final MessageProperties properties;
    private final UserClient userClient;

    // ==================== 渲染 ====================

    @Override
    public String render(String template, Map<String, Object> params) {
        if (template == null || params == null || params.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            // ★ 参数为 null 时也替换（渲染成"无"），否则会留下 {courseName} 这种残缺文案。
            //   注意与"参数根本不存在"区分：不存在的占位符保持原样，让配置错误暴露出来
            String value = e.getValue() == null ? "无" : String.valueOf(e.getValue());
            result = result.replace(
                    MessageConstants.PLACEHOLDER_PREFIX + e.getKey() + MessageConstants.PLACEHOLDER_SUFFIX,
                    value);
        }
        return result;
    }

    // ==================== 发送 ====================

    @Override
    public void sendByTemplate(String templateCode, Long userId, Map<String, Object> params, Long sourceId) {
        if (userId == null || templateCode == null || sourceId == null) {
            log.warn("通知参数不完整，已忽略：templateCode={}, userId={}, sourceId={}",
                    templateCode, userId, sourceId);
            return;
        }
        // 1. 查模板（按 code）
        NoticeTemplate tpl = templateMapper.getByCode(templateCode);
        if (tpl == null) {
            log.error("通知模板不存在，发送中止：code={}", templateCode);
            return;
        }
        if (tpl.getStatus() == null || tpl.getStatus() != MessageConstants.TEMPLATE_IN_SERVICE) {
            // 模板没启用（草稿/停用）→ 不发。这是运营的表达方式："先别发这条"
            log.info("通知模板未启用，跳过发送：code={}, status={}", templateCode, tpl.getStatus());
            return;
        }
        // 2. 渲染
        String title = render(tpl.getTitle(), params);
        String content = render(tpl.getContent(), params);
        // 3. 写用户信箱（★ 幂等在这里）
        LocalDateTime now = LocalDateTime.now();
        UserInbox inbox = new UserInbox()
                .setUserId(userId)
                .setType(tpl.getType() == null ? MessageConstants.TYPE_SYSTEM : tpl.getType())
                .setTitle(title)
                .setContent(content)
                .setIsRead(0)
                .setPublisher(null)
                .setPushTime(now)
                .setExpireTime(now.plusMonths(properties.getMessageTtlMonths()))
                .setSourceType(MessageConstants.SOURCE_BUSINESS)
                .setSourceId(sourceId);
        try {
            inboxMapper.insert(inbox);
        } catch (DuplicateKeyException e) {
            // 这笔业务已经通知过了 —— 正常情况（MQ 重投 / 上游重试），不是错误
            log.info("重复通知已忽略（幂等）：userId={}, code={}, sourceId={}", userId, templateCode, sourceId);
            return;
        }
        log.info("站内信已发送：userId={}, code={}, sourceId={}", userId, templateCode, sourceId);
        // 4. 可选发短信
        if (tpl.getIsSmsTemplate() != null && tpl.getIsSmsTemplate() == 1) {
            sendSms(tpl, userId, content);
        }
    }

    /**
     * 短信下发。
     *
     * <p>三个工程细节：</p>
     * <ol>
     *   <li><b>手机号查不到就跳过</b>（而不是抛异常）—— 短信是"锦上添花"，
     *       不能因为它失败就让站内信也发不成</li>
     *   <li><b>渠道异常被捕获</b>，同样不影响主流程</li>
     *   <li>★ <b>无论成功失败都落 sms_record</b> —— 失败也要留痕，
     *       否则"用户说没收到短信"时无法判断是没发还是发失败了</li>
     * </ol>
     */
    private void sendSms(NoticeTemplate tpl, Long userId, String content) {
        String phone = queryPhone(userId);
        if (phone == null || phone.isBlank()) {
            log.warn("用户手机号为空，短信跳过：userId={}, code={}", userId, tpl.getCode());
            return;
        }
        SmsRecord record = new SmsRecord()
                .setUserId(userId)
                .setPhone(phone)
                .setTemplateCode(tpl.getCode())
                .setContent(content);
        try {
            smsHandler.send(record);
        } catch (Exception e) {
            record.setStatus(MessageConstants.SMS_FAILED)
                    .setResultMsg("渠道异常：" + e.getMessage());
            log.error("短信下发异常：userId={}, code={}", userId, tpl.getCode(), e);
        }
        smsRecordMapper.insert(record);
        log.info("短信记录已留痕：userId={}, phone={}, status={}", userId, phone, record.getStatus());
    }

    /**
     * 查手机号：用户服务不可用时返回 null（降级为"只发站内信"）。
     *
     * <p>⚠️ 必须用 {@link UserClient#queryUserInternal}（免登录内部接口）而不是
     * {@code queryUserById}：本方法在 <b>MQ 消费线程</b>里被调用，
     * 那里没有用户上下文，而 {@code /users/{id}} 在 fm-user 的登录白名单里 → 401。
     * 踩过一次，日志形如
     * {@code FeignException$Unauthorized: [401] ... 请先登录}，
     * 表现为"站内信发了但短信没发"。</p>
     */
    private String queryPhone(Long userId) {
        try {
            UserDTO user = userClient.queryUserInternal(userId);
            return user == null ? null : user.getCellPhone();
        } catch (Exception e) {
            log.error("查询用户手机号失败，短信降级跳过：userId={}", userId, e);
            return null;
        }
    }

    // ==================== 模板管理 ====================

    @Override
    public Long saveTemplate(NoticeTemplateFormDTO dto) {
        if (templateMapper.getByCode(dto.getCode()) != null) {
            throw new BizIllegalException("模板代号已存在：" + dto.getCode());
        }
        NoticeTemplate tpl = new NoticeTemplate()
                .setName(dto.getName())
                .setCode(dto.getCode())
                .setType(dto.getType() == null ? MessageConstants.TYPE_SYSTEM : dto.getType())
                .setStatus(dto.getStatus() == null ? MessageConstants.TEMPLATE_DRAFT : dto.getStatus())
                .setTitle(dto.getTitle())
                .setContent(dto.getContent())
                .setIsSmsTemplate(dto.getIsSmsTemplate() == null ? 0 : dto.getIsSmsTemplate());
        templateMapper.insert(tpl);
        return tpl.getId();
    }

    @Override
    public void updateTemplate(NoticeTemplateFormDTO dto) {
        if (dto.getId() == null) {
            throw new BizIllegalException("模板id不能为空");
        }
        NoticeTemplate exist = templateMapper.selectById(dto.getId());
        if (exist == null) {
            throw new BizIllegalException("模板不存在");
        }
        // 代号不允许改：业务代码按它找模板，改了等于把线上的发送链路掐断
        if (dto.getCode() != null && !dto.getCode().equals(exist.getCode())) {
            throw new BizIllegalException("模板代号不允许修改");
        }
        templateMapper.updateById(new NoticeTemplate()
                .setId(dto.getId())
                .setName(dto.getName())
                .setType(dto.getType())
                .setStatus(dto.getStatus())
                .setTitle(dto.getTitle())
                .setContent(dto.getContent())
                .setIsSmsTemplate(dto.getIsSmsTemplate()));
    }

    @Override
    public void removeTemplate(Long id) {
        templateMapper.deleteById(id);
    }

    @Override
    public PageDTO<NoticeTemplate> queryTemplates(PageQuery query) {
        Page<NoticeTemplate> page = templateMapper.selectPage(query.toMpPage(), null);
        return PageDTO.of(page);
    }

    @Override
    public NoticeTemplate getTemplate(Long id) {
        return templateMapper.selectById(id);
    }
}
