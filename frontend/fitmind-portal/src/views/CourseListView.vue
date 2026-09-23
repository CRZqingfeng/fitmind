<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { pageTotal } from '@/utils/format'

import { Refresh, Search } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'

import { getCategoryList } from '@/api/course'
import { searchCourses } from '@/api/search'
import CourseCard from '@/components/CourseCard.vue'
import type { CategoryVO, CourseSearchQuery, CourseSearchVO } from '@/types/course'
import { COURSE_TYPES, GOAL_TYPES, RANK_BY_OPTIONS, TRAINING_LEVELS } from '@/types/course'

/**
 * 课程列表 / 搜索页
 *
 * ★ 设计要点：**URL 是筛选条件的唯一真相**。
 *   用户改筛选 → 写进 URL query → 由 watch(route.query) 统一触发请求。
 *   好处：
 *     · 链接可分享（把 URL 发给别人，看到的是同一批筛选结果）
 *     · 刷新不丢条件、浏览器前进后退可用
 *     · 只有一个请求入口，不会出现"改了两个筛选各发一次请求"
 *   代价是要注意别写出"写 URL → watch → 再写 URL"的循环（本页没有这个环）。
 */
const route = useRoute()
const router = useRouter()

const loading = ref(false)
const list = ref<CourseSearchVO[]>([])
const total = ref(0)
const categories = ref<CategoryVO[]>([])

/** 页面上的筛选状态（由 URL 单向驱动，见 readQueryFromUrl） */
const filters = reactive<{
  keyword: string
  pageNo: number
  pageSize: number
  categoryIdLv1?: string
  categoryIdLv2?: string
  categoryIdLv3?: string
  free?: boolean
  type?: number
  goalType?: number
  trainingLevel?: number
  rankBy: NonNullable<CourseSearchQuery['rankBy']>
  rankAsc: boolean
}>({
  keyword: '',
  pageNo: 1,
  pageSize: 12,
  rankBy: 'default',
  rankAsc: false,
})

/** 搜索框的独立状态：只有点搜索/回车才写进 URL（否则每敲一个字都会发请求） */
const keywordInput = ref('')

/** 级联选择器的值（[一级, 二级, 三级]，层级可缺省） */
const cascaderValue = ref<string[]>([])

/** el-cascader 需要的 {value,label,children} 结构 */
const cascaderOptions = computed(() =>
  categories.value.map((lv1) => ({
    value: lv1.id,
    label: lv1.name,
    children: (lv1.children || []).map((lv2) => ({
      value: lv2.id,
      label: lv2.name,
      children: (lv2.children || []).map((lv3) => ({ value: lv3.id, label: lv3.name })),
    })),
  })),
)

function str(v: unknown): string | undefined {
  return typeof v === 'string' && v ? v : undefined
}

function num(v: unknown): number | undefined {
  const n = Number(v)
  return Number.isFinite(n) && n > 0 ? n : undefined
}

/** 反向：把 URL query 读进页面状态 */
function readQueryFromUrl() {
  const q = route.query
  filters.keyword = str(q.keyword) ?? ''
  keywordInput.value = filters.keyword
  filters.pageNo = num(q.pageNo) ?? 1
  filters.pageSize = num(q.pageSize) ?? 12
  filters.categoryIdLv1 = str(q.lv1)
  filters.categoryIdLv2 = str(q.lv2)
  filters.categoryIdLv3 = str(q.lv3)
  filters.free = q.free === 'true' ? true : undefined
  filters.type = num(q.type)
  filters.goalType = num(q.goalType)
  filters.trainingLevel = num(q.trainingLevel)
  filters.rankBy = (str(q.rankBy) as typeof filters.rankBy) ?? 'default'
  filters.rankAsc = q.rankAsc === 'true'

  cascaderValue.value = [filters.categoryIdLv1, filters.categoryIdLv2, filters.categoryIdLv3]
    .filter((v): v is string => !!v)
}

/** 正向：把页面状态写进 URL（不直接发请求，交给 watch） */
function pushFiltersToUrl(patch: Partial<typeof filters> = {}) {
  Object.assign(filters, patch)
  const q: Record<string, string> = {}
  if (filters.keyword) q.keyword = filters.keyword
  if (filters.pageNo > 1) q.pageNo = String(filters.pageNo)
  if (filters.pageSize !== 12) q.pageSize = String(filters.pageSize)
  if (filters.categoryIdLv1) q.lv1 = filters.categoryIdLv1
  if (filters.categoryIdLv2) q.lv2 = filters.categoryIdLv2
  if (filters.categoryIdLv3) q.lv3 = filters.categoryIdLv3
  if (filters.free) q.free = 'true'
  if (filters.type) q.type = String(filters.type)
  if (filters.goalType) q.goalType = String(filters.goalType)
  if (filters.trainingLevel) q.trainingLevel = String(filters.trainingLevel)
  if (filters.rankBy !== 'default') q.rankBy = filters.rankBy
  if (filters.rankAsc) q.rankAsc = 'true'
  router.replace({ name: 'courses', query: q })
}

async function fetchList() {
  loading.value = true
  try {
    const res = await searchCourses({
      pageNo: filters.pageNo,
      pageSize: filters.pageSize,
      keyword: filters.keyword || undefined,
      categoryIdLv1: filters.categoryIdLv1,
      categoryIdLv2: filters.categoryIdLv2,
      categoryIdLv3: filters.categoryIdLv3,
      free: filters.free,
      type: filters.type,
      goalType: filters.goalType,
      trainingLevel: filters.trainingLevel,
      rankBy: filters.rankBy,
      rankAsc: filters.rankAsc,
    })
    list.value = res.list || []
    total.value = pageTotal(res)
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function fetchCategories() {
  try {
    categories.value = await getCategoryList({ status: 1, tree: true })
  } catch {
    categories.value = []
  }
}

// URL 变化 → 重新读条件 + 重新请求（唯一的请求入口）
watch(
  () => route.query,
  () => {
    readQueryFromUrl()
    fetchList()
  },
  { immediate: true },
)

// 分类树只取一次
fetchCategories()

function handleSearch() {
  pushFiltersToUrl({ keyword: keywordInput.value.trim(), pageNo: 1 })
}

/**
 * ★ 参数类型刻意用 `unknown` 而不是 `string[]`（M7-9）：
 *   `el-cascader` 的 `@change` 回调签名是 `(value: CascaderValue | null | undefined)`，
 *   而 `CascaderValue` 是"字符串 / 数字 / 数组"的联合类型 —— 声明成 `string[]`
 *   会**类型不兼容**。此前能过，只是因为按需引入前 `el-cascader` 在 TS 眼里
 *   是个"未知组件"、参数是 any（见 vite.config.ts 的说明）。
 *   反正下面本来就要用 Array.isArray 兜底，用 `unknown` 反而更诚实。
 */
function handleCascaderChange(val: unknown) {
  const arr = Array.isArray(val) ? (val as string[]) : []
  pushFiltersToUrl({
    categoryIdLv1: arr[0],
    categoryIdLv2: arr[1],
    categoryIdLv3: arr[2],
    pageNo: 1,
  })
}

function handleReset() {
  keywordInput.value = ''
  cascaderValue.value = []
  filters.rankBy = 'default'
  filters.rankAsc = false
  router.replace({ name: 'courses', query: {} })
}

function handlePageChange(page: number) {
  pushFiltersToUrl({ pageNo: page })
}

/** 是否处于"有筛选条件"状态（决定要不要显示「重置」） */
const hasFilters = computed(() =>
  !!filters.keyword || !!filters.categoryIdLv1 || !!filters.free || !!filters.type
  || !!filters.goalType || !!filters.trainingLevel || filters.rankBy !== 'default',
)
</script>

<template>
  <div class="fm-page">
    <el-card shadow="never" class="filter-card">
      <div class="search-row">
        <el-input
          v-model="keywordInput"
          data-testid="course-search-input"
          placeholder="搜索课程名 / 动作 / 教练 / 分类，例如「哑铃」"
          size="large"
          clearable
          :prefix-icon="Search"
          @keyup.enter="handleSearch"
        />
        <el-button
          data-testid="course-search-btn"
          type="primary"
          size="large"
          :loading="loading"
          @click="handleSearch"
        >
          搜索
        </el-button>
      </div>

      <div class="filter-row">
        <el-cascader
          v-model="cascaderValue"
          data-testid="course-category-cascader"
          :options="cascaderOptions"
          :props="{ checkStrictly: true, emitPath: true }"
          placeholder="全部分类"
          clearable
          @change="handleCascaderChange"
        />

        <el-select
          :model-value="filters.free === true ? 'free' : ''"
          placeholder="价格"
          clearable
          class="w120"
          @update:model-value="(v: string) => pushFiltersToUrl({ free: v === 'free' ? true : undefined, pageNo: 1 })"
        >
          <el-option label="只看免费" value="free" />
        </el-select>

        <el-select
          :model-value="filters.goalType"
          placeholder="训练目标"
          clearable
          class="w130"
          @update:model-value="(v: number) => pushFiltersToUrl({ goalType: v || undefined, pageNo: 1 })"
        >
          <el-option v-for="g in GOAL_TYPES" :key="g.value" :label="g.label" :value="g.value" />
        </el-select>

        <el-select
          :model-value="filters.trainingLevel"
          placeholder="适用水平"
          clearable
          class="w130"
          @update:model-value="(v: number) => pushFiltersToUrl({ trainingLevel: v || undefined, pageNo: 1 })"
        >
          <el-option v-for="t in TRAINING_LEVELS" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>

        <el-select
          :model-value="filters.type"
          placeholder="课程类型"
          clearable
          class="w140"
          @update:model-value="(v: number) => pushFiltersToUrl({ type: v || undefined, pageNo: 1 })"
        >
          <el-option v-for="c in COURSE_TYPES" :key="c.value" :label="c.label" :value="c.value" />
        </el-select>

        <el-select
          :model-value="filters.rankBy"
          class="w130"
          @update:model-value="(v: typeof filters.rankBy) => pushFiltersToUrl({ rankBy: v, pageNo: 1 })"
        >
          <el-option v-for="r in RANK_BY_OPTIONS" :key="r.value" :label="r.label" :value="r.value" />
        </el-select>

        <el-button
          v-if="filters.rankBy !== 'default'"
          text
          @click="pushFiltersToUrl({ rankAsc: !filters.rankAsc, pageNo: 1 })"
        >
          {{ filters.rankAsc ? '↑ 升序' : '↓ 降序' }}
        </el-button>

        <el-button v-if="hasFilters" text type="primary" :icon="Refresh" @click="handleReset">
          重置
        </el-button>
      </div>
    </el-card>

    <div class="result-bar">
      <span data-testid="course-total">
        共 <strong>{{ total }}</strong> 门课程
        <template v-if="filters.keyword">（关键词「{{ filters.keyword }}」）</template>
      </span>
      <el-tag v-if="filters.free" size="small" type="success" effect="plain">只看免费</el-tag>
    </div>

    <div v-loading="loading" class="course-grid" data-testid="course-grid">
      <CourseCard v-for="c in list" :key="c.id" :course="c" />

      <el-empty
        v-if="!loading && !list.length"
        class="empty"
        description="没有符合条件的课程，换个关键词或清掉筛选试试"
      />
    </div>

    <div v-if="total > filters.pageSize" class="pager">
      <el-pagination
        data-testid="course-pager"
        layout="prev, pager, next, total"
        :total="total"
        :page-size="filters.pageSize"
        :current-page="filters.pageNo"
        background
        @current-change="handlePageChange"
      />
    </div>
  </div>
</template>

<style scoped>
.filter-card {
  margin-bottom: 16px;
}

.search-row {
  display: flex;
  gap: 12px;
  margin-bottom: 14px;
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.w120 { width: 120px; }
.w130 { width: 130px; }
.w140 { width: 140px; }

.result-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.result-bar strong {
  color: var(--el-color-primary);
  font-size: 15px;
}

.course-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: 16px;
  min-height: 200px;
}

.empty {
  grid-column: 1 / -1;
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}
</style>
