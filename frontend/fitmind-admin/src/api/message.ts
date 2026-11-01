/**
 * 公告与通知模板（fm-message，网关前缀 `/sms`）
 *
 * ============================================================
 * ★★ 两组能力，别混为一谈
 * ============================================================
 *
 * | | 通知模板（`notice-templates`） | 公告（`public-notices`） |
 * |---|---|---|
 * | 面向谁 | **单个用户**（按模板给指定用户发，走内部接口） | **所有人** |
 * | 怎么发 | 程序按 `code` 找模板 → 填占位符 → 发 | 运营在这里直接发 |
 * | 本次交付 | CRUD + 分页 + 详情（5 个接口） | **发布** + 分页（2 个接口） |
 *
 * ============================================================
 * ★★ 公告的"定时"是靠一条**时间字段**实现的，没有任何调度器
 * ============================================================
 * `POST /admin/public-notices` 的 `pushTime`：**填未来时间 = 定时生效**。
 * 而学员端 `GET /sms/messages/page` 的注释写着"会先把**新**公告展开进来" ——
 * 也就是说"到时可见"是**读的时候按 `pushTime <= now` 过滤**出来的，不是推出来的。
 * → 所以验收里"定时公告当时不可见"这条断言**不需要等**，填个未来时间立刻就能验。
 *
 * ⚠️ 另一条容易踩的：**模板的 `code` 不可改**（`PUT` 的 `@Operation` 原话：
 *   "模板代号不可改"）。它是程序找模板的 key，改了等于把线上的调用打断。
 *   所以编辑表单里 `code` 要**只读**。
 */
import type {
  NoticeTemplate,
  NoticeTemplateForm,
  PageDTO,
  PublicNotice,
  PublicNoticeForm,
} from '@/types/admin'
import { http } from '@/utils/request'

// ==================== 通知模板 ====================

/** 新增模板 → 返回新模板 id */
export function createNoticeTemplate(body: NoticeTemplateForm): Promise<string> {
  return http.post<string>('/sms/admin/notice-templates', body)
}

/** 修改模板（★ `code` 不可改；`id` 必传） */
export function updateNoticeTemplate(body: NoticeTemplateForm): Promise<void> {
  return http.put<void>('/sms/admin/notice-templates', body)
}

/** 删除模板 */
export function deleteNoticeTemplate(id: string): Promise<void> {
  return http.delete<void>(`/sms/admin/notice-templates/${id}`)
}

/** 模板分页 */
export function getNoticeTemplatePage(query: {
  pageNo?: number
  pageSize?: number
}): Promise<PageDTO<NoticeTemplate>> {
  return http.get<PageDTO<NoticeTemplate>>('/sms/admin/notice-templates/page', { params: query })
}

/** 模板详情（编辑回填用） */
export function getNoticeTemplate(id: string): Promise<NoticeTemplate> {
  return http.get<NoticeTemplate>(`/sms/admin/notice-templates/${id}`)
}

// ==================== 公告 ====================

/**
 * 发布公告 → 返回公告 id。
 *
 * ★ `pushTime` 留空 = 立即生效；填未来时间 = **定时**（到点才被学员端查到）。
 * ★ `expireTime` 留空 = 按默认有效期。
 */
export function publishNotice(body: PublicNoticeForm): Promise<string> {
  return http.post<string>('/sms/admin/public-notices', body)
}

/** 公告分页 */
export function getPublicNoticePage(query: {
  pageNo?: number
  pageSize?: number
}): Promise<PageDTO<PublicNotice>> {
  return http.get<PageDTO<PublicNotice>>('/sms/admin/public-notices/page', { params: query })
}
