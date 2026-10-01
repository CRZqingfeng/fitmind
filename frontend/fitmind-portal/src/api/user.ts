import type { BodyProfile, BodyProfileForm, UpdateProfileForm, UserDetail } from '@/types/user'
import { http } from '@/utils/request'

/**
 * 用户相关接口（fm-user，网关前缀 /us）
 */

/**
 * 当前登录用户详情（GET /us/users/me）
 *
 * 依赖 Authorization 头，网关会解析 token 并把 userId 透传给下游服务，
 * 所以前端**不需要传 userId** —— 这也是为什么这个接口没有参数。
 */
export function getCurrentUser(): Promise<UserDetail> {
  return http.get<UserDetail>('/us/users/me')
}

// ==================== M7-12：资料编辑与身体档案 ====================

/**
 * 更新当前登录用户资料（也可改密码）。
 *
 * ⚠️ 后端 `UserFormDTO` 上**没有任何 `@Valid` 约束**，校验在 service 层 ——
 *   所以"哪些必填"得前端自己兜。尤其是**改密码**：填了新密码就必须带原密码，
 *   否则后端会拒（但报错信息不如前端自己拦来得清楚）。
 *
 * ★ 只提交"要改的字段"是安全的：后端按字段是否非空来更新（不传 = 不动）。
 */
export function updateCurrentUser(form: UpdateProfileForm): Promise<void> {
  return http.put<void>('/us/users/me', form)
}

/**
 * 我的身体档案。
 *
 * ★★ **未填写时返回的 `data` 是 `null`，不是空对象** ——
 *   调用方必须按空态处理（不要 `Object.keys()` 硬解）。
 *   这是后端 `BodyProfileController` 的 `@Operation` 原话：
 *   "获取当前用户的身体档案（未填写时 data 为 null）"。
 */
export function getBodyProfile(): Promise<BodyProfile | null> {
  return http.get<BodyProfile | null>('/us/users/me/profile')
}

/**
 * 保存身体档案（★ 首次是新增、之后是更新，同一个接口，幂等语义由后端处理）。
 *
 * ⚠️ 请求体里**没有 `bmi`** —— 后端按身高体重自己算，传了会被忽略。见 `BodyProfile` 的注释。
 */
export function saveBodyProfile(form: BodyProfileForm): Promise<void> {
  return http.put<void>('/us/users/me/profile', form)
}
