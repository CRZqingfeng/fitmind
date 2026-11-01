<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { addPlanItems, getPlanDict, pageExercises } from '@/api/plan'
import type { Exercise, PlanDict } from '@/types/plan'
import { pageTotal } from '@/utils/format'

/**
 * 动作库（M11-2 / P0）
 *
 * 两种模式（同一套界面，区别只在"底部有没有确认栏"）：
 *   · **选择模式**（带 `?dayId=`）：从编排页进来，勾选动作 → 底部「已选 N 个动作」→ 一次性提交
 *   · **浏览模式**（无 `dayId`）：从菜单/详情进来，点卡片看动作详情
 *
 * ★★ 两条关键设计：
 *
 * 1. **中途退出不落库**。选中状态只在前端，点"确认添加"才发一次
 *    `POST /plans/days/{dayId}/items`。边点边提交的话，用户误触一次就得回头删。
 *
 * 2. **分类与器械是 AND 关系**，且"胸/背/腿…"传的是 `bodyPart` 而不是 `category`
 *    （力量类动作按部位切）。左侧栏 15 项 = 全部 + 3 个分类（热身/拉伸/有氧）+ 11 个部位，
 *    全部由 `getPlanDict()` 驱动 —— 前端不硬编码任何一个中文词。
 */
const route = useRoute()
const router = useRouter()

const dayId = computed(() => (route.query.dayId ? String(route.query.dayId) : ''))
const planId = computed(() => (route.query.planId ? String(route.query.planId) : ''))
const selectMode = computed(() => !!dayId.value)

const ALL = '全部'
const loading = ref(true)
const errorText = ref('')
const dict = ref<PlanDict | null>(null)

const list = ref<Exercise[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(24)

const activeCategory = ref<string>(ALL)
const activeEquipment = ref<string>('')
const keyword = ref('')

/** 已选动作（选择模式）：id → 名称，顺序按点选顺序 */
const selected = ref<string[]>([])

/** 左侧栏 = 全部 + 分类 + 部位 */
const sidebar = computed<string[]>(() => [
  ALL,
  ...(dict.value?.categories ?? []),
  ...(dict.value?.bodyParts ?? []),
])

const isCategory = computed(() => (dict.value?.categories ?? []).includes(activeCategory.value))
const isBodyPart = computed(() => (dict.value?.bodyParts ?? []).includes(activeCategory.value))

async function loadDict() {
  dict.value = await getPlanDict()
}

async function loadList() {
  loading.value = true
  errorText.value = ''
  try {
    const page = await pageExercises({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      category: isCategory.value ? activeCategory.value : undefined,
      bodyPart: isBodyPart.value ? activeCategory.value : undefined,
      equipment: activeEquipment.value || undefined,
      keyword: keyword.value.trim() || undefined,
    })
    list.value = page?.list ?? []
    total.value = pageTotal(page)
  } catch (e) {
    // ★ 不吞成空列表：否则"接口挂了"会显示成"没有匹配的动作"，让人以为筛选条件写错了
    list.value = []
    total.value = 0
    errorText.value = e instanceof Error ? e.message : '动作库加载失败'
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

function applyFilter() {
  pageNo.value = 1
  loadList()
}

function pickCategory(c: string) {
  if (activeCategory.value === c) return
  activeCategory.value = c
  applyFilter()
}

function toggleEquipment(e: string) {
  activeEquipment.value = activeEquipment.value === e ? '' : e
  applyFilter()
}

function isSelected(id: string): boolean {
  return selected.value.includes(id)
}

function toggleSelect(ex: Exercise) {
  const idx = selected.value.indexOf(ex.id)
  if (idx >= 0) {
    selected.value.splice(idx, 1)
  } else {
    selected.value.push(ex.id)
  }
}

function openDetail(ex: Exercise) {
  if (selectMode.value) {
    toggleSelect(ex)
    return
  }
  router.push(`/plan/exercise/${ex.id}`)
}

const submitting = ref(false)

async function confirmAdd() {
  // ★ 必须先挡住"没有 dayId"的情况：dayId 为空时拼出来的地址是
  //   `/pls/plans/days//items`，Tomcat 会把双斜杠归一成 `/plans/days/items`，
  //   后端找不到映射 → 落到静态资源处理器 → 前端只看到一条
  //   "No static resource plans/days/items" 的 404，完全看不出是"少了训练日"。
  //   （2026-10-31 在 fm-plan 日志里真的出现过这一条。）
  if (!dayId.value) {
    ElMessage.error('没有指定训练日，请从「编排页 → 挑动作」进入')
    return
  }
  if (selected.value.length === 0) {
    ElMessage.warning('还没选动作')
    return
  }
  submitting.value = true
  try {
    await addPlanItems(dayId.value, selected.value)
    ElMessage.success(`已添加 ${selected.value.length} 个动作`)
    // 回编排页（不是 back()：用户可能是从动作库直接刷新进来的，back 会落到别处）
    if (planId.value) {
      router.push(`/plan/edit/${planId.value}`)
    } else {
      router.back()
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '添加失败')
  } finally {
    submitting.value = false
  }
}

function goBack() {
  router.back()
}
</script>

<template>
  <div class="exercise-lib" :class="{ 'with-bar': selectMode }">
    <div class="head">
      <el-button text @click="goBack">‹ 返回</el-button>
      <el-input
        v-model="keyword"
        placeholder="搜索动作（中文 / 英文）"
        clearable
        class="search"
        @keyup.enter="applyFilter"
        @clear="applyFilter"
      />
      <el-button @click="applyFilter">搜索</el-button>
    </div>

    <div class="body">
      <!-- 左侧 15 个分类：全部 + 热身/拉伸/有氧 + 11 个部位 -->
      <aside class="sidebar">
        <div
          v-for="c in sidebar"
          :key="c"
          class="side-item"
          :class="{ active: activeCategory === c }"
          @click="pickCategory(c)"
        >
          {{ c }}
        </div>
      </aside>

      <main class="main">
        <!-- 器械 chips（再点一次取消） -->
        <div class="chips">
          <span
            v-for="e in dict?.equipments ?? []"
            :key="e"
            class="chip"
            :class="{ active: activeEquipment === e }"
            @click="toggleEquipment(e)"
          >
            {{ e }}
          </span>
        </div>

        <el-alert v-if="errorText" type="error" :title="errorText" :closable="false" show-icon>
          <template #default>
            <el-button size="small" @click="applyFilter">重试</el-button>
          </template>
        </el-alert>

        <el-skeleton v-else-if="loading" :rows="4" animated />

        <el-empty v-else-if="list.length === 0" description="没有符合条件的动作" :image-size="70" />

        <div v-else class="grid">
          <div
            v-for="ex in list"
            :key="ex.id"
            class="ex-card"
            :class="{ picked: isSelected(ex.id) }"
            @click="openDetail(ex)"
          >
            <el-image :src="ex.imageUrl || ''" fit="cover" class="ex-img">
              <template #error>
                <div class="ex-ph">{{ ex.name.slice(0, 1) }}</div>
              </template>
            </el-image>
            <div class="ex-body">
              <div class="ex-name">{{ ex.name }}</div>
              <div class="ex-meta">
                <el-tag size="small" effect="plain" type="info">{{ ex.bodyPart }}</el-tag>
                <span class="ex-eq">{{ ex.equipment }}</span>
              </div>
            </div>
            <el-button
              v-if="selectMode"
              class="add-btn"
              size="small"
              :type="isSelected(ex.id) ? 'success' : 'primary'"
              @click.stop="toggleSelect(ex)"
            >
              {{ isSelected(ex.id) ? '已选' : '添加动作' }}
            </el-button>
            <span v-else class="detail-hint">详情 ›</span>
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

    <!-- 选择模式底部：已选计数 + 确认（一次性提交，中途退出不落库） -->
    <div v-if="selectMode" class="bottom-bar">
      <span class="selected-text">已选 {{ selected.length }} 个动作</span>
      <el-button @click="goBack">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="confirmAdd">确认添加</el-button>
    </div>
  </div>
</template>

<style scoped>
.exercise-lib {
  max-width: 1120px;
  margin: 0 auto;
}

.exercise-lib.with-bar {
  padding-bottom: 76px;
}

.head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.search {
  max-width: 320px;
}

.body {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}

.sidebar {
  width: 96px;
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

.side-item.active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}

.main {
  flex: 1;
  min-width: 0;
}

.chips {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.chip {
  padding: 4px 12px;
  font-size: 12px;
  border-radius: 14px;
  background: #fff;
  border: 1px solid var(--el-border-color);
  cursor: pointer;
  color: var(--el-text-color-regular);
}

.chip.active {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px;
}

.ex-card {
  position: relative;
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  overflow: hidden;
  cursor: pointer;
  transition: border-color 0.15s;
}

.ex-card:hover {
  border-color: var(--el-color-primary-light-5);
}

.ex-card.picked {
  border-color: var(--el-color-success);
  box-shadow: 0 0 0 1px var(--el-color-success-light-5) inset;
}

.ex-img {
  width: 100%;
  height: 110px;
  display: block;
  background: var(--el-fill-color-light);
}

.ex-ph {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
  color: var(--el-text-color-secondary);
}

.ex-body {
  padding: 8px 10px;
}

.ex-name {
  font-size: 14px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ex-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
}

.ex-eq {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.add-btn {
  margin: 0 10px 10px;
}

.detail-hint {
  display: block;
  padding: 0 10px 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.pager {
  margin-top: 14px;
  justify-content: center;
}

.bottom-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding: 12px 24px;
  background: #fff;
  border-top: 1px solid var(--el-border-color-light);
  z-index: 20;
}

.selected-text {
  margin-right: auto;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
</style>
