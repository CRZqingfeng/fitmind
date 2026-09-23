import { ref } from 'vue'
import { defineStore } from 'pinia'

import { getUnreadCount } from '@/api/message'

/**
 * 站内信未读数（M7-5）
 *
 * ★ 与购物车角标同一个套路（M7-4 定下来的）：
 *   ① 它是**每个页面都会用到的辅助信息**，所以状态放在 store 里只有一份；
 *   ② 拉取接口**静默失败**，服务抖一下绝不在用户正在看的页面上弹红条；
 *   ③ 但把"失败过"记下来（`loadFailed`），需要的地方可以据此提示。
 */
export const useMessageStore = defineStore('message', () => {
  const unread = ref(0)
  const loadFailed = ref(false)
  /** 是否已经拉过一次（用来区分"还没加载"和"确实是 0"） */
  const loaded = ref(false)

  async function load() {
    try {
      unread.value = (await getUnreadCount()) ?? 0
      loadFailed.value = false
      loaded.value = true
    } catch {
      loadFailed.value = true
    }
  }

  /**
   * 标完已读后重新拉一次。
   *
   * ★ 刻意**不在本地做 `-1`**：一条消息可能同时被"标记单条已读"和
   *   "点开详情自动标记已读"改到，本地减出来的数会和服务端对不上。
   *   多一次请求换一个准数，值。
   */
  async function refreshAfterRead() {
    await load()
  }

  /** 退出登录时清掉（否则换账号会看到上一个人的未读数） */
  function reset() {
    unread.value = 0
    loaded.value = false
    loadFailed.value = false
  }

  return { unread, loadFailed, loaded, load, refreshAfterRead, reset }
})
