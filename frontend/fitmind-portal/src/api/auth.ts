import type { LoginForm, StudentForm } from '@/types/user'
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

// ==================== M7-12：注册与忘记密码 ====================

/**
 * 检查手机号是否可注册。
 *
 * ★ 返回 `true` = **可以注册**（未被占用），不是"已存在" —— 语义容易读反。
 *   后端 `@Operation` 原文："检查手机号是否可用（未注册）"。
 *
 * ⚠️ ★★ **参数名是 `cellphone`，不是 `phone`**。
 *   `UserController.checkCellPhone(@RequestParam("cellphone") ...)`。
 *   传错名字会掉进"缺少必要参数" → **HTTP 200 + `code 500`「系统繁忙」**，
 *   而这个报错完全看不出是参数名的问题（实测踩过）。
 *
 * ✅ **（原为"要求登录、匿名页不可用"，#A16 已修）**
 *   修复前：网关 `requireLoginPath: /us/**` 覆盖了它（`excludePath` 只放行了
 *   `POST /us/codes` 与 `POST /us/students/register`），实测无 token → HTTP 401。
 *   在免登录的注册页调用它 → 全局响应拦截器判定"登录态过期" → 续期失败 →
 *   `forceLogin()` → **整页跳转到登录页**（等于把正在注册的用户踢走）。
 *   修复后：网关 `excludePath` 补了 `GET:/us/users/checkCellphone`，
 *   注册页的预校验已启用（见 `RegisterView.vue` 的 `handleCheckPhone`）。
 *
 * ⚠️ 放行的**代价**：任何人都能匿名探测"某手机号是否已注册"（枚举风险）。
 *   本项目为演示环境接受该权衡；生产环境应先加 IP 频控 / 图形验证码。
 */
export function checkCellphone(phone: string): Promise<boolean> {
  return http.get<boolean>('/us/users/checkCellphone', { params: { cellphone: phone } })
}

/**
 * 学员注册（★ 免登录，网关 `excludePath` 里专门放行了它）。
 *
 * ⚠️ `POST /us/codes` 拿到验证码后要**原样带回来**，后端会校验。
 *    Mock 模式下 `sendCode` 会把验证码直接返回给前端（开发便利），
 *    真实短信模式返回 null，此时必须手输。
 */
export function registerStudent(form: StudentForm): Promise<void> {
  return http.post<void>('/us/students/register', form)
}

/**
 * 通过验证码重置密码（忘记密码）。
 *
 * ★ 入参与注册**是同一个 DTO**（`StudentFormDTO`），`name` 字段会被后端忽略。
 *   所以这里复用 `StudentForm` 类型，不另造一个 —— 两边结构一致，再造一个只会漂移。
 */
export function resetPasswordByCode(form: StudentForm): Promise<void> {
  return http.put<void>('/us/students/password', form)
}
