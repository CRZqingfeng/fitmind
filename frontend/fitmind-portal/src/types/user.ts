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

// ============================================================
// M7-12：注册 / 忘记密码 / 资料编辑 / 身体档案
// ============================================================

/**
 * 注册与忘记密码的表单（后端 `fm-user` 的 `StudentFormDTO`，两个接口**共用同一个 DTO**）
 *
 *   · `POST /us/students/register`  注册 → 四个字段都生效
 *   · `PUT  /us/students/password`  忘记密码 → `name` 被忽略
 *
 * ⚠️ 后端校验：`cellPhone` 有 `@Pattern(^1[3-9]\d{9}$)`、`code` 与 `password` 都是 `@NotBlank`。
 *    前端镜像一份（别让用户撞服务端错误），但**不把它当唯一防线**。
 */
export interface StudentForm {
  cellPhone: string
  /** 短信验证码（Mock 模式下后端把它直接放在响应里返回，前端可自动回填） */
  code: string
  password: string
  /** 昵称（★ 只有注册用） */
  name?: string
}

/**
 * 用户资料表单（`PUT /us/users/me` 的请求体 → 后端 `UserFormDTO`）
 *
 * ⚠️ 后端 DTO 上**没有任何 `@Valid` 约束注解**，校验在 service 里 ——
 *    所以"哪些字段必填"要靠前端自己兜，不能指望 400 报错。
 *
 * ⚠️ 改密码是**同一接口**：填了 `password` 就必须同时填 `oldPassword`（后端会校验原密码）。
 *    这也是为什么这个表单要拆成"资料"和"改密"两块来组织，而不是混在一起提交。
 */
export interface UpdateProfileForm {
  /** 姓名 / 昵称 */
  name?: string | null
  /** 0-男性 1-女性 */
  gender?: number | null
  /** 头像地址（★ 存 `/ms/files` 返回的 `rawUrl`，不是带签名的 `url`） */
  icon?: string | null
  email?: string | null
  qq?: string | null
  /** `yyyy-MM-dd`（后端是 LocalDate） */
  birthday?: string | null
  province?: string | null
  city?: string | null
  district?: string | null
  /** 个人介绍 / 健身宣言 */
  intro?: string | null
  /** 形象照地址 */
  photo?: string | null
  /** 原密码（改密时必填） */
  oldPassword?: string | null
  /** 新密码（改密时必填） */
  password?: string | null
}

/** 训练目标（后端 `BodyProfileFormDTO.goalType`） */
export const GOAL_TYPE_OPTIONS = [
  { value: 1, label: '增肌' },
  { value: 2, label: '减脂' },
  { value: 3, label: '塑形' },
  { value: 4, label: '保持' },
] as const

/** 训练水平（后端 `BodyProfileFormDTO.trainingLevel`） */
export const TRAINING_LEVEL_OPTIONS = [
  { value: 1, label: '零基础' },
  { value: 2, label: '初级' },
  { value: 3, label: '中级' },
  { value: 4, label: '高级' },
] as const

/** 每周可训练次数的可选项（与训练计划的 1~7 约束对齐） */
export const WEEKLY_FREQUENCY_OPTIONS = [1, 2, 3, 4, 5, 6, 7] as const

/**
 * 身体档案（`GET /us/users/me/profile` 的 data；**从未填写时整个 data 为 `null`**）
 *
 * ★★ 两条类型约定（和 `types/assessment.ts` 完全一致，别混）：
 *   1. **没有 Long 字段**，所以这里看不到 `"id"` 那种字符串
 *   2. `BigDecimal`（身高/体重/各围度/bmi）是**数字**，不是字符串 ——
 *      `Long→String` 的全局规则只管 Long，不管 BigDecimal
 *
 * ★★ 入参里**没有 bmi**：后端按身高体重自己算。`BodyProfileFormDTO` 的类注释原话是
 *    "前端传了也会被忽略，避免出现「身高体重改了、BMI 还是旧值」的脏数据"。
 *    → 页面显示 BMI 一律用接口返回值，**前端一个公式都不重算**
 *      （与体测页同一口径，M7-6 §16.3 踩过"页面自己算 → 和接口不一致"的坑）。
 */
export interface BodyProfile {
  height: number | null
  weight: number | null
  targetWeight: number | null
  /** ★ 后端自动计算，只读 */
  bmi: number | null
  bodyFatRate: number | null
  waist: number | null
  chest: number | null
  arm: number | null
  hip: number | null
  goalType: number | null
  /** 后端给的中文名（前端不自己映射，避免两边文案不一致） */
  goalTypeName: string | null
  trainingLevel: number | null
  trainingLevelName: string | null
  weeklyFrequency: number | null
  injuryHistory: string | null
  updateTime: string | null
}

/**
 * 身体档案表单（`PUT /us/users/me/profile` 的请求体）
 *
 * ⚠️ **不要传 `bmi`** —— 后端不认，传了也会被忽略（见上面 `BodyProfile` 的说明）。
 */
export interface BodyProfileForm {
  height?: number | null
  weight?: number | null
  targetWeight?: number | null
  bodyFatRate?: number | null
  waist?: number | null
  chest?: number | null
  arm?: number | null
  hip?: number | null
  goalType?: number | null
  trainingLevel?: number | null
  weeklyFrequency?: number | null
  injuryHistory?: string | null
}
