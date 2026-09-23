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
