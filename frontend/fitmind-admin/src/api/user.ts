import type { CourseTeacherVO } from '@/types/admin'
import type { UserDetail } from '@/types/user'
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

// ==================== 建课用：查教练（M7-12 第 2 期） ====================

/**
 * 按手机号查用户 id。
 *
 * ★★ 这是建课第 5 步"绑教练"的**唯一入口** —— 因为全项目
 *   **没有"列出全部教练"的接口**：
 *   `GET /us/users/coaches` 的 `ids` 是**必填参数**，漏传会得到
 *   **HTTP 200 + `code 500`「系统繁忙，请稍后再试」**
 *   —— 那个报错完全看不出是"缺参数"（第 1 期开工前实测踩过）。
 *   它只能"按 id 批量取"，所以链路是**两步**：
 *   `手机号 → 查 id → 批量取教练信息`。
 *
 * ⚠️ ★★ **返回的是单个 id，不是数组**（后端签名 `Long exchangeUserIdWithPhone(String)`）。
 *   而且**查不到用户时它会直接抛业务异常**
 *   （`AssertUtils.isNotNull(user, USER_ID_NOT_EXISTS)`），不是返回 null。
 *   → 调用方要 `try/catch`，不要写成"判断空数组"。
 *   第一版就按数组写了，`ids.join(',')` 抛 `join is not a function`，
 *   表现是"点了查询没反应"（2026-09-29 验收时踩到并修正）。
 *
 * @returns 命中该手机号的用户 id
 */
export function findUserIdByPhone(phone: string): Promise<string> {
  return http.get<string>('/us/users/ids', { params: { phone } })
}

/**
 * 按 id 批量取教练信息（姓名 / 头像 / 简介）。
 *
 * ⚠️ 传进来的人**不一定真的是教练**（`type=3`）—— 这个接口按 id 批量取用户，
 *   不做 type 过滤。所以建课向导在页面上把类型也显示出来，
 *   让运营自己确认"选的是不是教练"，而不是替后端假装校验过。
 */
export function getCoaches(ids: string[]): Promise<CourseTeacherVO[]> {
  return http.get<CourseTeacherVO[]>('/us/users/coaches', { params: { ids: ids.join(',') } })
}
