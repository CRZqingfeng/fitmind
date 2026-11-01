<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { createSportLog, getSportDict, pageSports } from '@/api/plan'
import type { Sport, SportDict } from '@/types/plan'
import { pageTotal } from '@/utils/format'

/**
 * 记运动（P1）
 *
 * ★★ 关于"实时显示本次消耗"（提示词原话：kcal 由后端算、前端只展示）：
 *   这里弹层里的数字是**预览**，用与后端完全相同的公式
 *   （`kcal_per_30min × 分钟 / 30`，四舍五入）算出来给用户看；
 *   **保存后用后端返回的 kcal 覆盖**，并把它显示在成功提示里。
 *   如果保存后不以后端为准，"我看到的 260"和"库里记的 259"就会差一卡。
 *
 * ★ 运动消耗**不抵扣预算**（口径 1）：这不是漏了，是刻意 ——
 *   抵扣会变成"越练越能吃"，把用户往补偿性进食上引。
 */
const route = useRoute()
const router = useRouter()
const date = String(route.query.date || '')

const dict = ref<SportDict | null>(null)
const loading = ref(true)
const errorText = ref('')

const list = ref<Sport[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(24)

const activeCategory = ref<string>('')
const keyword = ref('')

const dialogVisible = ref(false)
const saving = ref(false)
const current = ref<Sport | null>(null)
const duration = reactive({ minutes: 30 })
/** 保存后以后端返回值为准 */
const lastResult = ref<{ kcal: number; text: string } | null>(null)

const sidebar = computed(() => dict.value?.entries ?? [])

/** 预览消耗（与后端同公式，四舍五入） */
const previewKcal = computed(() => {
  if (!current.value) return 0
  return Math.round((current.value.kcalPer30min * duration.minutes) / 30)
})

async function loadDict() {
  dict.value = await getSportDict()
}

async function loadList() {
  loading.value = true
  errorText.value = ''
  try {
    const page = await pageSports({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      category: activeCategory.value || undefined,
      keyword: keyword.value.trim() || undefined,
    })
    list.value = page?.list ?? []
    total.value = pageTotal(page)
  } catch (e) {
    list.value = []
    total.value = 0
    errorText.value = e instanceof Error ? e.message : '运动库加载失败'
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

function pickEntry(key: string, kind: string) {
  // 「动作库」是跳页面（kind=link），不是筛选
  if (kind === 'link') {
    router.push('/plan/exercise-lib')
    return
  }
  if (kind === 'search') {
    keyword.value = ''
    activeCategory.value = ''
  } else if (kind === 'entry') {
    activeCategory.value = ''
  } else {
    activeCategory.value = key
  }
  pageNo.value = 1
  loadList()
}

function search() {
  pageNo.value = 1
  loadList()
}

function openDialog(sport: Sport) {
  current.value = sport
  duration.minutes = 30
  lastResult.value = null
  dialogVisible.value = true
}

async function confirm() {
  if (!current.value) return
  if (!duration.minutes || duration.minutes < 1) {
    ElMessage.warning('时长至少 1 分钟')
    return
  }
  saving.value = true
  try {
    const res = await createSportLog({
      date: date || undefined,
      sportId: current.value.id,
      durationMin: duration.minutes,
    })
    lastResult.value = { kcal: res.kcal, text: res.text }
    // 显示后端算出来的数（而不是预览的那个）
    ElMessage.success(`已记录：${res.text}`)
    dialogVisible.value = false
    backToHome()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '记录失败')
  } finally {
    saving.value = false
  }
}

function backToHome() {
  router.push({ path: '/plan', query: { tab: 'diet', date: date || undefined } })
}
</script>

<template>
  <div class="sport-lib">
    <div class="head">
      <el-button text @click="backToHome">‹ 返回</el-button>
      <span class="title">记运动</span>
      <el-input
        v-model="keyword"
        placeholder="搜索运动"
        clearable
        class="search"
        @keyup.enter="search"
        @clear="search"
      />
      <el-button @click="search">搜索</el-button>
    </div>

    <div class="body">
      <aside class="sidebar">
        <div
          v-for="e in sidebar"
          :key="e.key"
          class="side-item"
          :class="{ active: activeCategory === e.key, category: e.kind === 'category' }"
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

        <el-empty v-else-if="list.length === 0" description="这里还没有运动项目" :image-size="70" />

        <div v-else class="grid">
          <div v-for="s in list" :key="s.id" class="sport-card">
            <div class="info">
              <div class="name">{{ s.name }}</div>
              <div class="kcal">{{ s.kcalText }}</div>
            </div>
            <el-button circle size="small" type="primary" @click="openDialog(s)">＋</el-button>
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

    <el-dialog v-model="dialogVisible" :title="current?.name || '记运动'" width="360px">
      <div class="dialog-base">
        <span>基准消耗</span>
        <b>{{ current?.kcalPer30min }} 千卡/30分钟</b>
      </div>
      <div class="big-input">
        <el-input-number
          v-model="duration.minutes"
          :min="1"
          :max="600"
          :step="5"
          size="large"
          controls-position="right"
        />
        <span class="unit">分钟</span>
      </div>
      <div class="preview">
        本次约 <b>{{ previewKcal }}</b> 千卡
        <span class="tip-sub">（以后端返回值为准）</span>
      </div>
      <div class="note">运动消耗只做记录，不会加到「还可以吃」里。</div>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="confirm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.sport-lib {
  max-width: 1000px;
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
}

.search {
  max-width: 260px;
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

.sport-card {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  padding: 12px;
}

.info {
  flex: 1;
  min-width: 0;
}

.name {
  font-size: 14px;
}

.kcal {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}

.pager {
  margin-top: 14px;
  justify-content: center;
}

.dialog-base {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 12px;
}

.big-input {
  display: flex;
  align-items: center;
  gap: 10px;
}

.big-input :deep(.el-input-number) {
  width: 180px;
}

.unit {
  font-size: 14px;
  color: var(--el-text-color-regular);
}

.preview {
  margin-top: 14px;
  font-size: 14px;
}

.preview b {
  font-size: 20px;
  color: var(--el-color-primary);
}

.tip-sub {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.note {
  margin-top: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
