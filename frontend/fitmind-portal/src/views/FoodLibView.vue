<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { createDietLog, createFood, favoriteFood, getFoodDict, pageFoods, unfavoriteFood } from '@/api/plan'
import type { Food, FoodDict } from '@/types/plan'
import { pageTotal } from '@/utils/format'

/**
 * 饮食库（四个「记早餐/午餐/晚餐/加餐」入口共用这一个页面，靠 query 的 mealType 区分）
 *
 * ★★ 两个关键设计：
 *
 * 1. **点「＋」必须能选份量**。只给一个加号就直接按 100g 记，
 *    用户永远记不了"1 碗"——而份量选错比记错食物更常见（米饭 1 碗是 150g，不是 100g）。
 *    单位列表来自 `food.units`，前端不写死"克/碗/盒"。
 *
 * 2. **左侧 12 项里只有 8 个是分类**。"常见/收藏/我添加的/搜索"是**入口**，
 *    由后端 `entries[].kind` 标明（entry / search / category）。
 *    把它们当成分类塞进 category 参数，服务端会查不到任何东西 —— 而且不报错。
 */
const route = useRoute()
const router = useRouter()

const mealType = Number(route.query.mealType || 1)
const date = String(route.query.date || '')
const mealLabel = computed(() => ['早餐', '午餐', '晚餐', '加餐'][mealType - 1] || '餐')

const dict = ref<FoodDict | null>(null)
const loading = ref(true)
const errorText = ref('')

const list = ref<Food[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(24)

/** 当前选中的左侧项：scope（入口）与 category（分类）二选一，由 kind 决定 */
const activeKey = ref<string>('common')
const activeKind = ref<'entry' | 'search' | 'category'>('entry')
const keyword = ref('')

const sidebar = computed(() => dict.value?.entries ?? [])

/** 份量弹层 */
const portionVisible = ref(false)
const portionSaving = ref(false)
const current = ref<Food | null>(null)
const portion = reactive({ amount: 100, unit: '克' })

/** 自定义食物弹层 */
const customVisible = ref(false)
const customSaving = ref(false)
const customForm = reactive({
  name: '',
  category: '',
  kcalPer100g: 0,
  proteinPer100g: 0,
  carbPer100g: 0,
  fatPer100g: 0,
})

async function loadDict() {
  dict.value = await getFoodDict()
  if (!customForm.category) {
    customForm.category = dict.value.categories[0] ?? ''
  }
}

async function loadList() {
  loading.value = true
  errorText.value = ''
  try {
    const page = await pageFoods({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      scope: activeKind.value === 'category' ? 'common' : activeKey.value,
      category: activeKind.value === 'category' ? activeKey.value : undefined,
      keyword: keyword.value.trim() || undefined,
    })
    list.value = page?.list ?? []
    total.value = pageTotal(page)
  } catch (e) {
    list.value = []
    total.value = 0
    errorText.value = e instanceof Error ? e.message : '食物库加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    await loadDict()
  } catch (e) {
    errorText.value = e instanceof Error ? e.message : '字典加载失败'
    loading.value = false
    return
  }
  await loadList()
})

function pickEntry(key: string, kind: 'entry' | 'search' | 'category') {
  // 「搜索」这个左侧项不切换筛选，只是把焦点交给搜索框（这里简单处理成清空条件）
  if (kind === 'search') {
    keyword.value = ''
    activeKey.value = 'common'
    activeKind.value = 'entry'
    pageNo.value = 1
    loadList()
    return
  }
  activeKey.value = key
  activeKind.value = kind
  pageNo.value = 1
  loadList()
}

function search() {
  pageNo.value = 1
  loadList()
}

/** 点＋：打开份量弹层，默认用该食物的默认单位 + 100（或 1 个非克单位） */
function openPortion(food: Food) {
  current.value = food
  portion.unit = food.defaultUnit || '克'
  portion.amount = portion.unit === '克' ? 100 : 1
  portionVisible.value = true
}

function unitGramOf(food: Food, unit: string): number {
  return food.units.find((u) => u.unit === unit)?.gram ?? 1
}

/** 弹层里的实时预览（只是估算显示；真正落库的 kcal 由后端算） */
const previewGram = computed(() => {
  if (!current.value) return 0
  return portion.amount * unitGramOf(current.value, portion.unit)
})
const previewKcal = computed(() => {
  if (!current.value) return 0
  return Math.round((current.value.kcalPer100g * previewGram.value) / 100)
})

async function confirmPortion() {
  if (!current.value) return
  if (!portion.amount || portion.amount <= 0) {
    ElMessage.warning('份量要大于 0')
    return
  }
  portionSaving.value = true
  try {
    const res = await createDietLog({
      date: date || undefined,
      mealType,
      foodId: current.value.id,
      amount: portion.amount,
      unit: portion.unit,
    })
    // 后端会告诉我们"已记 50克"还是"已累加为 100克"（合并场景）
    ElMessage.success(res.message || '已记录')
    portionVisible.value = false
    backToHome()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '记录失败')
  } finally {
    portionSaving.value = false
  }
}

/**
 * 回饮食首页。
 *
 * ★ 必须带 `?tab=diet&date=`：本项目没有 <keep-alive>，路由切回来会重新挂载，
 *   但**不要依赖**这一点 —— 哪天有人为了"保留滚动位置"套上 keep-alive，
 *   首页的数字就会永远停在旧值，而且没有任何报错。显式带 query 是最稳的。
 */
function backToHome() {
  router.push({ path: '/plan', query: { tab: 'diet', date: date || undefined } })
}

async function toggleFavorite(food: Food) {
  try {
    if (food.favorite) {
      await unfavoriteFood(food.id)
    } else {
      await favoriteFood(food.id)
    }
    food.favorite = !food.favorite
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

async function submitCustom() {
  if (!customForm.name.trim()) {
    ElMessage.warning('食物名不能为空')
    return
  }
  if (!customForm.category) {
    ElMessage.warning('先选一个分类')
    return
  }
  customSaving.value = true
  try {
    await createFood({
      name: customForm.name.trim(),
      category: customForm.category,
      kcalPer100g: customForm.kcalPer100g,
      proteinPer100g: customForm.proteinPer100g || undefined,
      carbPer100g: customForm.carbPer100g || undefined,
      fatPer100g: customForm.fatPer100g || undefined,
    })
    ElMessage.success('已添加，可在「我添加的」里找到')
    customVisible.value = false
    activeKey.value = 'mine'
    activeKind.value = 'entry'
    pageNo.value = 1
    await loadList()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败')
  } finally {
    customSaving.value = false
  }
}
</script>

<template>
  <div class="food-lib">
    <div class="head">
      <el-button text @click="backToHome">‹ 返回</el-button>
      <span class="title">记{{ mealLabel }}</span>
      <el-input
        v-model="keyword"
        placeholder="搜索食物"
        clearable
        class="search"
        @keyup.enter="search"
        @clear="search"
      />
      <el-button @click="search">搜索</el-button>
      <el-button type="primary" plain @click="customVisible = true">+ 自定义食物</el-button>
    </div>

    <div class="body">
      <!-- 左侧：入口 + 分类（kind 由后端标明，前端不猜） -->
      <aside class="sidebar">
        <div
          v-for="e in sidebar"
          :key="e.key"
          class="side-item"
          :class="{ active: activeKey === e.key && activeKind === e.kind, category: e.kind === 'category' }"
          @click="pickEntry(e.key, e.kind)"
        >
          {{ e.label }}
        </div>
      </aside>

      <main class="main">
        <el-alert v-if="errorText" type="error" :title="errorText" :closable="false" show-icon>
          <template #default>
            <el-button size="small" @click="loadList">重试</el-button>
          </template>
        </el-alert>

        <el-skeleton v-else-if="loading" :rows="4" animated />

        <el-empty v-else-if="list.length === 0" description="这里还没有食物" :image-size="70" />

        <div v-else class="grid">
          <div v-for="food in list" :key="food.id" class="food-card">
            <span class="emoji">{{ food.emoji || '🍽' }}</span>
            <div class="info">
              <div class="name">{{ food.name }}</div>
              <div class="kcal">{{ food.kcalText }}</div>
            </div>
            <el-button
              class="fav"
              text
              size="small"
              @click="toggleFavorite(food)"
            >
              {{ food.favorite ? '★' : '☆' }}
            </el-button>
            <el-button class="add" circle size="small" type="primary" @click="openPortion(food)">
              ＋
            </el-button>
          </div>
        </div>

        <el-pagination
          v-if="total > pageSize"
          v-model:current-page="pageNo"
          :page-size="pageSize"
          :total="total"
          layout="prev, pager, next"
          class="pager"
          @current-change="loadList"
        />
      </main>
    </div>

    <!-- 份量弹层 -->
    <el-dialog v-model="portionVisible" :title="current?.name || '选份量'" width="380px">
      <div class="portion-row">
        <el-input-number v-model="portion.amount" :min="0.1" :max="2000" :step="10" :precision="1" />
        <el-select v-model="portion.unit" class="unit-select">
          <el-option v-for="u in current?.units ?? []" :key="u.unit" :label="u.unit" :value="u.unit" />
        </el-select>
      </div>
      <div class="portion-tip">
        约 <b>{{ previewGram }}</b> 克 · 约 <b>{{ previewKcal }}</b> 千卡
        <span class="tip-sub">（最终热量以后端计算为准）</span>
      </div>
      <template #footer>
        <el-button @click="portionVisible = false">取消</el-button>
        <el-button type="primary" :loading="portionSaving" @click="confirmPortion">保存</el-button>
      </template>
    </el-dialog>

    <!-- 自定义食物 -->
    <el-dialog v-model="customVisible" title="自定义食物" width="440px">
      <el-form label-width="88px">
        <el-form-item label="食物名">
          <el-input v-model="customForm.name" placeholder="例如：妈妈做的红烧肉" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="customForm.category" class="full">
            <el-option v-for="c in dict?.categories ?? []" :key="c" :label="c" :value="c" />
            <el-option label="中式菜肴" value="中式菜肴" />
            <el-option label="西式菜肴" value="西式菜肴" />
          </el-select>
        </el-form-item>
        <el-form-item label="每100g热量">
          <el-input-number v-model="customForm.kcalPer100g" :min="0" :max="900" />
        </el-form-item>
        <el-form-item label="蛋白/100g">
          <el-input-number v-model="customForm.proteinPer100g" :min="0" :max="100" :precision="1" />
        </el-form-item>
        <el-form-item label="碳水/100g">
          <el-input-number v-model="customForm.carbPer100g" :min="0" :max="100" :precision="1" />
        </el-form-item>
        <el-form-item label="脂肪/100g">
          <el-input-number v-model="customForm.fatPer100g" :min="0" :max="100" :precision="1" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="customVisible = false">取消</el-button>
        <el-button type="primary" :loading="customSaving" @click="submitCustom">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.food-lib {
  max-width: 1120px;
  margin: 0 auto;
}

.head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.title {
  font-size: 17px;
  font-weight: 600;
  white-space: nowrap;
}

.search {
  max-width: 280px;
}

.body {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}

.sidebar {
  width: 104px;
  flex-shrink: 0;
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  padding: 6px;
  max-height: 70vh;
  overflow-y: auto;
  position: sticky;
  top: 12px;
}

.side-item {
  padding: 8px 6px;
  font-size: 13px;
  text-align: center;
  border-radius: 8px;
  cursor: pointer;
  color: var(--el-text-color-regular);
}

.side-item.category {
  font-size: 12px;
}

.side-item.active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}

.main {
  flex: 1;
  min-width: 0;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 10px;
}

.food-card {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  padding: 10px 12px;
}

.emoji {
  font-size: 24px;
  width: 34px;
  text-align: center;
  flex-shrink: 0;
}

.info {
  flex: 1;
  min-width: 0;
}

.name {
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kcal {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.fav {
  color: var(--el-color-warning);
}

.add {
  flex-shrink: 0;
}

.pager {
  margin-top: 14px;
  justify-content: center;
}

.portion-row {
  display: flex;
  gap: 10px;
  align-items: center;
}

.unit-select {
  width: 100px;
}

.portion-tip {
  margin-top: 12px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.tip-sub {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.full {
  width: 100%;
}
</style>
