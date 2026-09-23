import { computed, ref } from 'vue'

import { defineStore } from 'pinia'

import { adminLogin, logout as logoutApi } from '@/api/auth'
import { getCurrentUser } from '@/api/user'
import type { LoginForm, UserDetail } from '@/types/user'
import { USER_TYPE_ADMIN, USER_TYPE_COACH } from '@/types/user'
import { clearToken, getToken, setToken } from '@/utils/token'

/**
 * 登录态 store（管理端）
 *
 * 与 fitmind-portal 的差异：登录调 adminLogin、角色标签只认管理员/教练。
 * 其余逻辑（token 写入时机、finally 清本地状态）完全一致。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(getToken())
  const user = ref<UserDetail | null>(null)

  const isLogin = computed(() => !!token.value)

  const displayName = computed(() => {
    const u = user.value
    return u?.name || u?.username || u?.cellPhone || ''
  })

  /** 管理端只可能出现管理员（1）和教练（3）——学员账号登不进来 */
  const roleLabel = computed(() => {
    switch (user.value?.type) {
      case USER_TYPE_ADMIN:
        return '管理员'
      case USER_TYPE_COACH:
        return '教练'
      default:
        return ''
    }
  })

  /** 只有管理员能改配置类功能（AI 运营等），教练只读 —— M7-8 会用上 */
  const isAdmin = computed(() => user.value?.type === USER_TYPE_ADMIN)

  async function login(form: LoginForm): Promise<void> {
    const newToken = await adminLogin(form)
    // ★ 先写 token 再拉用户信息：反过来 fetchMe 会因为读不到 token 而 401
    setToken(newToken)
    token.value = newToken
    await fetchMe()
  }

  async function fetchMe(): Promise<UserDetail> {
    const detail = await getCurrentUser()
    user.value = detail
    return detail
  }

  async function logout(): Promise<void> {
    try {
      await logoutApi()
    } finally {
      clearToken()
      token.value = null
      user.value = null
    }
  }

  return { token, user, isLogin, displayName, roleLabel, isAdmin, login, fetchMe, logout }
})
