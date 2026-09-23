/**
 * 用户相关类型
 *
 * 字段来源：
 *   - UserDetail    ← fm-user 的 UserDetailVO（GET /us/users/me）
 *   - LoginForm     ← fm-api 的 LoginFormDTO（POST /as/accounts/login）
 */

/** 用户类型（后端 UserDetailVO.type） */
export const USER_TYPE_ADMIN = 1
export const USER_TYPE_STUDENT = 2
export const USER_TYPE_COACH = 3

/** 登录方式（后端 LoginFormDTO.LOGIN_TYPE_*） */
export const LOGIN_TYPE_PASSWORD = 1
export const LOGIN_TYPE_CODE = 2

/** 性别（后端约定） */
export const GENDER_MALE = 0
export const GENDER_FEMALE = 1

/**
 * 用户详情（GET /us/users/me 的 data）
 *
 * 注：后端字段可能为 null（例如未填生日），这里标成 nullable 而不是用可选符 `?`
 * —— 后端返回的是 `"birthday": null` 这种"键存在、值为 null"，
 * 用 `?` 描述会和实际不符（`?` 表示键可能不存在）。
 */
export interface UserDetail {
  /**
   * ⚠️ 类型是 **string** 不是 number。
   *
   * 后端 `JacksonConfig` 里有全局配置：
   *   `serializerByType(Long.class, ToStringSerializer.instance)`
   * 所有 Java Long 都会被序列化成字符串（防止 JS 大整数精度丢失）。
   * 实测 `GET /us/users/me` 返回的就是 `"id":"1"`。
   * 同理，本项目**所有**以 Long 为类型的字段（各种 id、金额分值等）前端都应声明为 string。
   */
  id: string
  cellPhone: string
  username: string | null
  name: string | null
  gender: number | null
  icon: string | null
  email: string | null
  qq: string | null
  birthday: string | null
  province: string | null
  city: string | null
  district: string | null
  intro: string | null
  photo: string | null
  type: number
  roleName: string | null
  status: number
  createTime: string | null
}

/** 登录表单（POST /as/accounts/login 的请求体） */
export interface LoginForm {
  /** 1-密码登录，2-验证码登录 */
  type: number
  /** 密码登录用 */
  username?: string
  /** 验证码登录用 */
  cellPhone?: string
  /** 密码或验证码 */
  password: string
  /** 7 天免密（后端据此决定 refresh Cookie 的 maxAge） */
  rememberMe?: boolean
}
