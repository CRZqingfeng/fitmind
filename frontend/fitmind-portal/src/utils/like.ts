import { likeBiz } from '@/api/remark'

/**
 * 可点赞对象的公共形态（回答 `Reply`、笔记 `Note` 都符合）
 *
 * 只声明真正用到的三个字段，这样传 `Reply` 或 `Note` 都可以 ——
 * 不写成 `Likeable = Reply | Note` 是为了避免 util 反向依赖 types 里的业务类型。
 */
export interface LikeableTarget {
  id: string
  liked: boolean | null
  likedTimes: number | null
}

/**
 * 点赞 / 取消点赞，带**乐观更新 + 失败回滚**。
 *
 * ============================================================
 * 为什么不是"调完接口就重新拉列表"（这是最初的写法，但体验是坏的）
 * ============================================================
 * 这条链路有**两套一致性**（实测，见 M7 方案 §十五）：
 *
 *  · `liked`（我赞没赞过）—— **立刻一致**。
 *    fm-learning 返回列表时会实时向 fm-remark 查 Redis SET，
 *    所以接口调用一返回，服务端的 `liked` 就已经是新值了。
 *
 *  · `likedTimes`（点赞总数）—— **最终一致，最长 20 秒**。
 *    fm-remark 的 `addLikeRecord` 只做两件事：写 Redis SET + **标脏**；
 *    真正的计数由 xxl-job `likedTimesSyncJob`（`0/20 * * * * ?`，每 20 秒一轮）
 *    弹出脏标、按集合基数**全量**同步给 fm-learning。
 *
 * 所以"点完立刻重新拉列表"拿到的是**旧的** `likedTimes`：
 * 用户点了赞、心形立刻变红，**但数字纹丝不动，而且不会自己再刷新** ——
 * 要等下次进页面才变。用户会以为没点上。
 *
 * ============================================================
 * 为什么本次的乐观更新不会出现"连点两下本地 +2"
 * ============================================================
 * 关键前提：**`liked` 与服务端同步翻转**。
 *   · 第一次点击：本地 `liked`(false) → true，发 `liked: true`，计数 +1
 *   · 第二次点击：本地 `liked`(true) → false，发 `liked: false`，计数 -1
 *   → 本地净 0，服务端 like→unlike 净 0，**两边一致**。
 * 只有"本地 `liked` 不跟着翻转、只闷头加计数"才会 +2 —— 那正是要避免的写法。
 *
 * 而且就算本地状态因为别处（多标签页 / 多设备）改动而偏了，
 * 服务端是**按集合基数全量同步**的幂等语义，下一轮同步就会自动收敛到真值。
 * 换句话说：这里的乐观值只是"预测"，**它会自愈**。
 *
 * 注：`likeBiz` 的返回值是 `void`，接口不告诉我们"本次是否真的产生了变更"，
 * 所以预测只能基于本地 `liked`。这是这个方案唯一的假设，也是它需要自愈机制的原因。
 */
export async function toggleLikeWithRollback(
  target: LikeableTarget,
  bizType: string,
): Promise<void> {
  const next = !target.liked
  const prevLiked = target.liked
  const prevTimes = target.likedTimes ?? 0

  target.liked = next
  target.likedTimes = Math.max(0, prevTimes + (next ? 1 : -1))

  try {
    await likeBiz({ bizId: target.id, bizType, liked: next })
    // ★ 刻意**不**在这里重新拉列表：
    //   服务端计数最长 20 秒后才同步，立刻重拉只会把界面从"正确的新值"打回"还没同步的旧值"。
  } catch {
    // 失败就把预测撤回（request.ts 已经弹过提示了）
    target.liked = prevLiked
    target.likedTimes = prevTimes
    throw new Error('点赞失败')
  }
}
