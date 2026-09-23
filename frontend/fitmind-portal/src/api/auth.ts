import type { LoginForm } from '@/types/user'
import { http } from '@/utils/request'

/**
 * 认证相关接口（fm-auth / fm-user，网关前缀 /as、/us）
 */

/**
 * 发送短信验证码
 *
 * ★ Mock 模式下后端**直接把验证码放在 data 里返回**（`R.ok(code)`），
 *   所以开发/演示环境前端可以自动回填，不用真的收短信。
 *   真实短信模式返回 null，此时必须手输。
 */
export function sendCode(phone: string): Promise<string | null> {
  return http.post<string | null>('/us/codes', null, { params: { phone } })
}

/**
 * 登录，返回 accessToken（裸 token，直接放 Authorization 头）
 *
 * silent：登录失败（密码/验证码错，后端返回 HTTP 401）由登录页内联展示，
 * 不用全局 toast —— 否则用户看到的是"左下角弹一句 + 表单没反应"，不如直接标在输入框下
 */
export function login(form: LoginForm): Promise<string> {
  return http.post<string>('/as/accounts/login', form, { silent: true })
}

/** 管理端登录（走独立的 admin 接口，refresh Cookie 名也不同：admin-refresh） */
export function adminLogin(form: LoginForm): Promise<string> {
  return http.post<string>('/as/accounts/admin/login', form, { silent: true })
}

/** 退出登录：后端会清 jti 缓存 + 清 refresh Cookie */
export function logout(): Promise<void> {
  return http.post<void>('/as/accounts/logout', null, { silent: true })
}
