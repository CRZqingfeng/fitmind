<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import { Delete, ShoppingCart, Van } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'

import { prePlaceOrder } from '@/api/trade'
import { useCartStore } from '@/stores/cart'
import type { CartItem } from '@/types/trade'
import { formatPrice } from '@/utils/format'

/**
 * 购物车（M7-3）
 *
 * 两个刻意的设计：
 *  1. **只读服务端价格**：页面上的金额全部来自 `GET /ts/carts` 返回的 `price`
 *     （不是加入时的快照）。课程可能在你加购之后调价，展示旧价会误导。
 *  2. **"去结算"先做一次预校验**：调用下单调的 `prePlace`（它只读不写库），
 *     让"课程已下架 / 已购买 / 有未支付旧单"这类问题在**进结算页之前**就暴露。
 *     结算页还会再调一次拿权威数据 —— 两次都是只读的，多一次换来的是
 *     "别让用户填完一屏信息才被告知下不了单"。
 */
const router = useRouter()
const cart = useCartStore()

/** 已勾选的课程 id */
const selected = ref<string[]>([])
const submitting = ref(false)
/**
 * 封面加载失败的课程（→ 用渐变色兜底）。
 * ★ 用普通对象而不是 Set：`ref(new Set())` 里的增删**不会**触发视图更新
 *   （Vue 的响应式不追踪 Set 的方法调用），页面上会看到一张破图迟迟不消失。
 */
const coverFailed = reactive<Record<string, boolean>>({})

const rows = computed(() => cart.items)

/** 可下单的条目（失效 / 已购买的不参与结算） */
const selectableIds = computed(() =>
  cart.items.filter((i) => !i.invalid && !i.bought).map((i) => i.courseId),
)
const selectedAmount = computed(() =>
  cart.items
    .filter((i) => selected.value.includes(i.courseId))
    .reduce((sum, i) => sum + (i.price ?? 0), 0),
)
const allSelected = computed(
  () => selectableIds.value.length > 0 && selected.value.length === selectableIds.value.length,
)
const indeterminate = computed(
  () => selected.value.length > 0 && selected.value.length < selectableIds.value.length,
)

onMounted(async () => {
  await cart.load()
  // 默认勾选所有可下单的（多数人进购物车就是想全买）
  selected.value = [...selectableIds.value]
})

function toggleAll(v: string | number | boolean) {
  selected.value = v ? [...selectableIds.value] : []
}

function toggleOne(courseId: string) {
  selected.value = selected.value.includes(courseId)
    ? selected.value.filter((id) => id !== courseId)
    : [...selected.value, courseId]
}

async function handleRemove(item: CartItem) {
  await cart.remove(item.courseId)
  selected.value = selected.value.filter((id) => id !== item.courseId)
  ElMessage.success('已移出购物车')
}

async function handleClear() {
  try {
    await ElMessageBox.confirm('确定要清空购物车吗？', '提示', {
      type: 'warning',
      confirmButtonText: '清空',
      cancelButtonText: '取消',
    })
  } catch {
    return // 用户点了取消
  }
  await cart.clear()
  selected.value = []
  ElMessage.success('购物车已清空')
}

async function handleCheckout() {
  const ids = selected.value.filter((id) => selectableIds.value.includes(id))
  if (ids.length === 0) {
    ElMessage.warning('请先勾选要结算的课程')
    return
  }
  submitting.value = true
  try {
    // 预校验（只读）：失败会由 request.ts 统一弹出后端给的原因
    await prePlaceOrder(ids)
    router.push({ path: '/checkout', query: { courseIds: ids.join(',') } })
  } catch {
    /* 校验不通过，留在购物车页（用户能看到是哪门课的问题） */
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="cart-page">
    <div class="page-head">
      <h2>购物车</h2>
      <span class="head-count" data-testid="cart-count">共 {{ cart.count }} 门课程</span>
    </div>

    <!-- ★ 「加载失败」与「购物车是空的」必须分开显示。
         混在一起的话，接口一挂用户就会以为自己加购的课不见了。 -->
    <el-result
      v-if="!cart.loading && cart.loadFailed"
      icon="warning"
      title="购物车加载失败"
      sub-title="可能是服务暂时不可用，重试即可（你的购物车不会丢）"
      data-testid="cart-load-failed"
    >
      <template #extra>
        <el-button type="primary" @click="cart.load()">重新加载</el-button>
      </template>
    </el-result>

    <el-empty
      v-else-if="!cart.loading && rows.length === 0"
      description="购物车还是空的"
      data-testid="cart-empty"
    >
      <el-button type="primary" :icon="ShoppingCart" @click="router.push('/courses')">
        去挑课程
      </el-button>
    </el-empty>

    <template v-else>
      <el-card v-loading="cart.loading" shadow="never" class="list-card">
        <div class="list-head">
          <el-checkbox
            :model-value="allSelected"
            :indeterminate="indeterminate"
            :disabled="selectableIds.length === 0"
            @change="toggleAll"
          >
            全选
          </el-checkbox>
          <el-button link type="danger" :icon="Delete" @click="handleClear">清空购物车</el-button>
        </div>

        <div v-for="item in rows" :key="item.courseId" class="cart-row" data-testid="cart-row">
          <el-checkbox
            class="row-check"
            :model-value="selected.includes(item.courseId)"
            :disabled="Boolean(item.invalid) || Boolean(item.bought)"
            @change="toggleOne(item.courseId)"
          />

          <div class="row-cover" @click="router.push(`/course/${item.courseId}`)">
            <img
              v-if="item.coverUrl && !coverFailed[item.courseId]"
              :src="item.coverUrl"
              :alt="item.courseName"
              @error="coverFailed[item.courseId] = true"
            />
            <div v-else class="cover-fallback">{{ item.courseName.slice(0, 1) }}</div>
          </div>

          <div class="row-info">
            <div class="row-name" @click="router.push(`/course/${item.courseId}`)">
              {{ item.courseName }}
            </div>
            <div class="row-tags">
              <el-tag v-if="item.bought" type="info" size="small">已购买，无需重复下单</el-tag>
              <el-tag v-else-if="item.invalid" type="danger" size="small">
                {{ item.invalidReason || '已失效' }}
              </el-tag>
              <el-tag
                v-else-if="item.addedPrice != null && item.addedPrice !== item.price"
                :type="item.price < item.addedPrice ? 'success' : 'warning'"
                size="small"
              >
                {{ item.price < item.addedPrice ? '已降价' : '已涨价' }}
                （加购时 {{ formatPrice(item.addedPrice) }}）
              </el-tag>
            </div>
          </div>

          <div class="row-price" data-testid="cart-price">{{ formatPrice(item.price) }}</div>

          <el-button
            class="row-del"
            link
            type="danger"
            :icon="Delete"
            title="移出购物车"
            @click="handleRemove(item)"
          />
        </div>
      </el-card>

      <div class="bar">
        <div class="bar-left">
          <span>已选 <b data-testid="cart-selected-count">{{ selected.length }}</b> 门</span>
          <span class="bar-total">
            合计 <b data-testid="cart-total">{{ formatPrice(selectedAmount) }}</b>
          </span>
          <span class="bar-hint">最终金额以提交订单时服务端计算为准（优惠券在下一步选择）</span>
        </div>
        <el-button
          type="primary"
          size="large"
          :icon="Van"
          :loading="submitting"
          data-testid="cart-checkout"
          @click="handleCheckout"
        >
          去结算
        </el-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.cart-page {
  max-width: 980px;
  margin: 0 auto;
}

.page-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 16px;
}

.page-head h2 {
  margin: 0;
  font-size: 20px;
}

.head-count {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.list-card :deep(.el-card__body) {
  padding: 0;
}

.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.cart-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.cart-row:last-child {
  border-bottom: none;
}

.row-check {
  flex-shrink: 0;
}

.row-cover {
  width: 96px;
  height: 64px;
  flex-shrink: 0;
  border-radius: 6px;
  overflow: hidden;
  cursor: pointer;
  background: var(--el-fill-color-light);
}

.row-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.cover-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, #ff8a5b, #e5533d);
}

.row-info {
  flex: 1;
  min-width: 0;
}

.row-name {
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-name:hover {
  color: var(--el-color-primary);
}

.row-tags {
  margin-top: 6px;
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.row-price {
  font-size: 17px;
  font-weight: 700;
  color: var(--el-color-danger);
  flex-shrink: 0;
}

.row-del {
  flex-shrink: 0;
}

.bar {
  position: sticky;
  bottom: 0;
  margin-top: 16px;
  padding: 14px 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  box-shadow: 0 -2px 12px rgb(0 0 0 / 6%);
}

.bar-left {
  display: flex;
  align-items: baseline;
  gap: 16px;
  flex-wrap: wrap;
}

.bar-total b {
  font-size: 20px;
  color: var(--el-color-danger);
}

.bar-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
