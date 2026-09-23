import { computed, ref } from 'vue'

import { defineStore } from 'pinia'

import { login as loginApi, logout as logoutApi } from '@/api/auth'
import { getCurrentUser } from '@/api/user'
import type { LoginForm, UserDetail } from '@/types/user'
import { USER_TYPE_ADMIN, USER_TYPE_COACH, USER_TYPE_STUDENT } from '@/types/user'
import { clearToken, getToken, setToken } from '@/utils/token'

/**
 * 登录态 store
 *
 * 后端概念映射：Pinia store ≈ IoC 容器里的单例 Bean ——
 * 全局唯一、任何组件都能注入（`const auth = useAuthStore()`），
 * 组件卸载不会销毁它（生命周期跟着 app 走，不是跟着组件走）。
 *
 * 职责边界：
 *   - token 的**存储**不在这里（在 utils/token.ts，为了打破循环依赖，见那里的注释）
 *   - 这里负责 token 的**读写时机**（登录时写入、退出时清除）和用户信息缓存
 */
export const useAuthStore = defineStore('auth', () => {
  /** 当前 accessToken（初值从 localStorage 恢复，实现"刷新页面不掉登录"） */
  const token = ref<string | null>(getToken())
  /** 当前用户详情。登出后为 null；懒加载（首次需要时才请求 /us/users/me） */
  const user = ref<UserDetail | null>(null)

  const isLogin = computed(() => !!token.value)

  /** 展示名优先级：姓名/昵称 → 用户名 → 手机号 */
  const displayName = computed(() => {
    const u = user.value
    return u?.name || u?.username || u?.cellPhone || ''
  })

  /** 角色中文名（后端 UserDetailVO.type：1-管理员 2-学员 3-教练） */
  const roleLabel = computed(() => {
    switch (user.value?.type) {
      case USER_TYPE_ADMIN:
        return '管理员'
      case USER_TYPE_COACH:
        return '教练'
      case USER_TYPE_STUDENT:
        return '学员'
      default:
        return ''
    }
  })

  /**
   * 登录
   *
   * ★ 顺序很重要：先 setToken 再 fetchMe。
   *   反过来的话，fetchMe 发请求时拦截器读不到 token，必然 401。
   */
  async function login(form: LoginForm): Promise<void> {
    const newToken = await loginApi(form)
    setToken(newToken)
    token.value = newToken
    await fetchMe()
  }

  /** 拉取当前用户详情 */
  async function fetchMe(): Promise<UserDetail> {
    const detail = await getCurrentUser()
    user.value = detail
    return detail
  }

  /** 退出登录 */
  async function logout(): Promise<void> {
    try {
      await logoutApi()
    } finally {
      // ★ finally 而不是 try 之后：后端登出失败（网络断了）时本地也必须清干净。
      //   否则用户点了"退出"却还停在登录态，比"后端没清掉"更让人困惑。
      clearToken()
      token.value = null
      user.value = null
    }
  }

  return { token, user, isLogin, displayName, roleLabel, login, fetchMe, logout }
})
