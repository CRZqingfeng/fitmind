import type { LoginForm } from '@/types/user'
import { http } from '@/utils/request'

/**
 * 管理端认证接口
 *
 * ★ 与用户端（fitmind-portal）的差异只有一处：登录接口不同。
 *   `POST /as/accounts/admin/login` 在后端以 `isStaff=true` 调用校验，
 *   要求账号类型**不是学员**（即管理员或教练）；用户端则要求必须是学员。
 *   两边用反了会直接报"非学员/非管理端账号"。
 *
 * ⚠️ 一个已核实、但暂不修改的后端实现细节：
 *   管理端与用户端写的是**同名 Cookie**（都叫 `refresh`）——
 *   `AuthConstants.ADMIN_REFRESH_HEADER` 定义了却没被使用。
 *   同机开发时两个端会互相覆盖 refresh Cookie（HttpOnly Cookie 按 host 存，不分端口）。
 *   因为 accessToken 存在 localStorage（按 origin 隔离）且 TTL 30 天，
 *   实际影响很小，但**验收时不要假设另一端的登录态还在**。
 */

/** 管理端登录，返回 accessToken（裸 token） */
export function adminLogin(form: LoginForm): Promise<string> {
  return http.post<string>('/as/accounts/admin/login', form, { silent: true })
}

/**
 * 发送短信验证码
 *
 * 与用户端共用同一个公开接口（网关 excludePath 里放行，不需要登录）。
 * Mock 模式下后端直接返回验证码，前端自动回填。
 */
export function sendCode(phone: string): Promise<string | null> {
  return http.post<string | null>('/us/codes', null, { params: { phone } })
}

/** 退出登录（与用户端共用同一接口） */
export function logout(): Promise<void> {
  return http.post<void>('/as/accounts/admin/logout', null, { silent: true })
}
