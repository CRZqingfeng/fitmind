import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { addToCart, clearCart, getCart, removeCartItem } from '@/api/trade'
import type { CartItem } from '@/types/trade'

/**
 * 购物车状态（M7-3）
 *
 * ★ 为什么需要这个 store，而不是每个页面各自调 `getCart()`：
 *   顶栏要显示"购物车有几件"。如果每个页面各拿一份，就会出现
 *   "在详情页加购了，但顶栏角标还是旧数字"——用户在同一个页面里
 *   就能看到两个自相矛盾的数字。购物车有**唯一一份**状态，是这个组件结构的前提。
 *
 * ⚠️ 它和后端的购物车是"镜像"关系，不是"副本"：
 *   所有写操作都先打后端、成功后再刷新本地，**不在前端做乐观更新**
 *   （加购这件事后端会校验课程是否上架/是否已购买，前端猜不准）。
 */
export const useCartStore = defineStore('cart', () => {
  const items = ref<CartItem[]>([])
  const loading = ref(false)
  /** 后端是否已经拉过一次（用来区分"还没加载"和"确实是空的"） */
  const loaded = ref(false)
  /**
   * 上次加载是否失败。
   *
   * ★ 必须与 `loaded` 分开：只有 `loaded=false` 的话，
   *   购物车页会把"接口挂了"渲染成"购物车还是空的" ——
   *   用户会以为自己刚加购的课不见了。这两个状态必须能区分。
   */
  const loadFailed = ref(false)

  /** 顶栏角标：用总条目数（含失效的），因为用户看到的"购物车里有什么"就是这些 */
  const count = computed(() => items.value.length)

  /** 可下单的条目（失效 / 已购买的不能结算） */
  const checkoutable = computed(() => items.value.filter((i) => !i.invalid && !i.bought))

  /** 可下单条目的合计金额，单位分。★ 仅用于展示，真实金额以下单接口为准 */
  const totalAmount = computed(() =>
    checkoutable.value.reduce((sum, i) => sum + (i.price ?? 0), 0),
  )

  async function load() {
    loading.value = true
    try {
      // ★ 静默拉取：角标是辅助信息，失败不弹全局红条（见 api/trade.ts 的注释）
      items.value = (await getCart()) ?? []
      loaded.value = true
      loadFailed.value = false
    } catch {
      // 失败时保持原状（没登录时不该把角标清零），但把"失败过"记下来给购物车页用
      loadFailed.value = true
    } finally {
      loading.value = false
    }
  }

  async function add(courseId: string) {
    await addToCart(courseId)
    await load()
  }

  async function remove(courseId: string) {
    await removeCartItem(courseId)
    // 本地先摘掉，避免整表重拉导致列表"跳一下"
    items.value = items.value.filter((i) => i.courseId !== courseId)
  }

  async function clear() {
    await clearCart()
    items.value = []
  }

  /** 退出登录时调用：把本地镜像清掉，否则换账号登录会看到上一个人的购物车 */
  function reset() {
    items.value = []
    loaded.value = false
    loadFailed.value = false
  }

  return {
    items,
    loading,
    loaded,
    loadFailed,
    count,
    checkoutable,
    totalAmount,
    load,
    add,
    remove,
    clear,
    reset,
  }
})
