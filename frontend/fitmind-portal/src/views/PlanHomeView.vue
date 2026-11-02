<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import { ElMessage, ElMessageBox } from 'element-plus'
import type { LocationQueryRaw } from 'vue-router'
import { useRoute, useRouter } from 'vue-router'

import {
  abandonPlan,
  getMyPlans,
  getPlanDict,
  getRecommendPlans,
  getTodayWorkout,
  startWorkout,
} from '@/api/plan'
import DietHome from '@/components/plan/DietHome.vue'
import PlanCard from '@/components/plan/PlanCard.vue'
import type { MyPlan, PlanCard as PlanCardType, PlanDict, TodayWorkout } from '@/types/plan'
import { TodayStatus } from '@/types/plan'

/**
 * 计划首页（M11-2 / P0）
 *
 * 结构：日期 + 两个分钟数 → 周历条 → 今日训练卡 → 推荐计划 / 我的计划
 *
 * ★★ 三条必须守住的规矩（都是踩过坑的）：
 *
 * 1. **今日训练卡是"三态"，不是"有没有计划"两态**
 *    `0 无计划 / 1 今天休息 / 2 今天要练` 各有各的样子。
 *    把 0 和 1 合成"hasPlan=false"的后果：用户明明在执行计划，
 *    首页却喊"还没有计划，快去执行吧"，然后他真的又去执行了一遍。
 *
 * 2. **两个分钟数只读后端**（`todayMinutes` / `totalMinutes`）。
 *    前端自己 SUM 一遍 session 的话，会和后端差一次四舍五入，演示时对不上。
 *
 * 3. **loading / 空 / 出错三态分开**。
 *    loading 期间落进"空态"会先闪一下"还没有计划"，再跳成"今天要练"——
 *    用户看到的是页面在抽风，而不是"正在加载"。
 */
const router = useRouter()
const route = useRoute()

const TAB_TRAIN = 'train'
const TAB_DIET = 'diet'
const TAB_MINE = 'mine'
const TABS = [TAB_TRAIN, TAB_DIET, TAB_MINE]

/**
 * tab 与 URL 双向同步（`?tab=diet&date=2026-10-31`）。
 *
 * ★★ 为什么非要同步：
 *   本项目**没有** <keep-alive>（DefaultLayout 里只有裸 <RouterView />），
 *   所以从饮食库返回时页面会重新挂载、onMounted 会重拉 —— 现在能跑。
 *   但**不能依赖这个**：哪天有人为了"保留滚动位置"套上 keep-alive，
 *   首页的数字就会永远停在旧值，而且没有任何报错。
 *   显式带 query 之后，"回首页要刷新"这件事是被 URL 保证的，不靠组件生命周期。
 */
const activeTab = ref<string>(normalizeTab(String(route.query.tab || '')))
/** 饮食 tab 选中的日期（来自 query，用于看历史某天） */
const dietDate = ref<string>(String(route.query.date || ''))

function normalizeTab(tab: string): string {
  return TABS.includes(tab) ? tab : TAB_TRAIN
}

/** tab 变了 → 写回 URL（replace：不往浏览历史里塞一堆 tab 记录） */
watch(activeTab, (val) => {
  // ★ 必须显式标注成 LocationQueryRaw：spread 之后 TS 会把类型收窄成 { tab: string }，
  //   后面 delete query.date 就会报 "Property 'date' does not exist"
  const query: LocationQueryRaw = { ...route.query, tab: val }
  if (val !== TAB_DIET) {
    delete query.date
  }
  router.replace({ path: '/plan', query })
})

/** URL 变了（例如从饮食库跳回来）→ 跟随 */
watch(
  () => [route.query.tab, route.query.date],
  ([tab, date]) => {
    activeTab.value = normalizeTab(String(tab || ''))
    dietDate.value = String(date || '')
  },
)

const loading = ref(true)
const errorText = ref('')
const today = ref<TodayWorkout | null>(null)
const recommends = ref<PlanCardType[]>([])
const mine = ref<MyPlan[]>([])
const dict = ref<PlanDict | null>(null)
const goalFilter = ref<string>('')

/** 周历条的周偏移（0 = 本周，-1 = 上周） */
const weekOffset = ref(0)

const todayLabel = computed(() => {
  const d = new Date()
  const week = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 周${week}`
})

/**
 * 周历条：从周一开始排 7 天。
 * ★ JS 的 getDay() 里周日是 0，而"一周从周一开始"要把 0 当成 7 处理，
 *   不处理的话周末那一格会跑到最左边去。
 */
const weekDays = computed(() => {
  const now = new Date()
  const dow = (now.getDay() + 6) % 7
  const monday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - dow + weekOffset.value * 7)
  const labels = ['一', '二', '三', '四', '五', '六', '日']
  return labels.map((label, i) => {
    const d = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + i)
    const sameDay =
      d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate()
    return { key: `${d.getMonth() + 1}-${d.getDate()}-${i}`, label, day: d.getDate(), isToday: sameDay }
  })
})

/** 今天是"本周"时，左右翻周的语义才不至于让人困惑 */
const weekTitle = computed(() => {
  if (weekOffset.value === 0) return '本周'
  return weekOffset.value > 0 ? `后 ${weekOffset.value} 周` : `前 ${-weekOffset.value} 周`
})

async function load() {
  loading.value = true
  errorText.value = ''
  try {
    // 三个请求互不依赖，并行发；任何一个失败都进 catch（不要用 Promise.allSettled 吞掉原因）
    const [t, r, m, d] = await Promise.all([
      getTodayWorkout(),
      getRecommendPlans(goalFilter.value || undefined),
      getMyPlans(),
      getPlanDict(),
    ])
    today.value = t
    recommends.value = r ?? []
    mine.value = m ?? []
    dict.value = d ?? null
  } catch (e) {
    // ★ 不能把异常吞成"空列表"：那样页面会显示"还没有推荐计划"，
    //   而真实原因是接口挂了 —— 假空态比报错更难查
    errorText.value = e instanceof Error ? e.message : '加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function reloadRecommend() {
  try {
    recommends.value = await getRecommendPlans(goalFilter.value || undefined)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '推荐计划加载失败')
  }
}

function openPlan(id: string) {
  router.push(`/plan/detail/${id}`)
}

/** 开始今日训练：后端幂等，重复进入返回同一个 session */
async function startToday() {
  if (!today.value?.planDayId) return
  if (today.value.sessionFinished && today.value.sessionId) {
    router.push(`/plan/workout/${today.value.sessionId}`)
    return
  }
  try {
    const sessionId = await startWorkout(today.value.planDayId)
    router.push(`/plan/workout/${sessionId}`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '开始训练失败')
  }
}

/** 「定制计划」→ AI 结果页（M11-4 / P2 起已接通） */
function goCustom() {
  router.push('/plan/ai')
}

/**
 * 「我的计划」的展示顺序：**已放弃的沉底**，其余按后端给的顺序（update_time desc）。
 *
 * ★ 为什么不改后端 SQL：放弃会让 update_time 变新，那条记录自然跳到最前 ——
 *   而"放弃的"恰恰是最不需要被看见的。这是纯展示诉求，前端排一次就够，
 *   后端排序保持"最近动过的在前"这个通用语义不变。
 */
const mineSorted = computed(() => {
  const alive = mine.value.filter((p) => p.status !== 2)
  const abandoned = mine.value.filter((p) => p.status === 2)
  return [...alive, ...abandoned]
})

/** 正在请求停止的计划 id（防连点；同时也给按钮做 loading） */
const abandoningId = ref<string>('')

/**
 * 停止执行某个计划。
 *
 * ★ 二次确认是必须的：这个动作会让「今日训练」直接换一份（或变回"还没有计划"），
 *   而首页是用户最常待的地方，误点一下就把正在练的计划停掉，很难解释。
 * ★ 后端幂等 + 已练完会被拒，所以这里不再自己判状态，只把后端文案原样弹出来。
 */
async function handleAbandon(p: MyPlan) {
  if (abandoningId.value) return
  try {
    await ElMessageBox.confirm(
      `停止后「${p.title}」不再出现在今日训练里，已练过的记录都会保留。确定停止吗？`,
      '停止执行计划',
      { type: 'warning', confirmButtonText: '停止执行', cancelButtonText: '再想想' },
    )
  } catch {
    return // 用户点了取消
  }
  abandoningId.value = p.id
  try {
    await abandonPlan(p.id)
    ElMessage.success('已停止执行，可以重新挑一个计划了')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '停止失败')
  } finally {
    abandoningId.value = ''
  }
}

async function createEmpty() {
  router.push('/plan/edit/new')
}

function goPlans() {
  document.getElementById('recommend-section')?.scrollIntoView({ behavior: 'smooth' })
}
</script>

<template>
  <div class="plan-home">
    <!-- 顶部：日期 + 状态徽标 -->
    <div class="hero">
      <div class="hero-left">
        <div class="date">{{ todayLabel }}</div>
        <div class="hero-sub">
          <el-tag v-if="today" size="small" effect="dark" :type="today.hasPlan ? 'primary' : 'info'">
            {{ today.statusLabel }}
          </el-tag>
          <span v-if="today?.planTitle" class="plan-title">{{ today.planTitle }}</span>
        </div>
      </div>

      <div class="minutes">
        <div class="minute-item">
          <div class="num">{{ today?.todayMinutes ?? 0 }}</div>
          <div class="label">今日训练（分钟）</div>
        </div>
        <el-divider direction="vertical" />
        <div class="minute-item">
          <div class="num">{{ today?.totalMinutes ?? 0 }}</div>
          <div class="label">累计训练（分钟）</div>
        </div>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="plan-tabs">
      <!-- ==================== 训练 ==================== -->
      <el-tab-pane label="训练" :name="TAB_TRAIN">
        <!-- 周历条 -->
        <div class="week-bar">
          <el-button text @click="weekOffset--">‹</el-button>
          <div class="week-days">
            <div
              v-for="d in weekDays"
              :key="d.key"
              class="week-day"
              :class="{ today: d.isToday }"
            >
              <span class="wd-label">{{ d.label }}</span>
              <span class="wd-day">{{ d.day }}</span>
            </div>
          </div>
          <el-button text @click="weekOffset++">›</el-button>
          <span class="week-title">{{ weekTitle }}</span>
        </div>

        <!-- 三态：加载中 / 出错 / 业务三态 -->
        <el-skeleton v-if="loading" :rows="3" animated class="card-skeleton" />

        <el-alert
          v-else-if="errorText"
          type="error"
          :title="errorText"
          :closable="false"
          show-icon
        >
          <template #default>
            <el-button size="small" @click="load">重试</el-button>
          </template>
        </el-alert>

        <template v-else-if="today">
          <!-- 态一：还没有执行的计划 -->
          <div v-if="today.status === TodayStatus.NO_PLAN" class="today-card muted">
            <div class="big-text">当前没有执行的训练计划</div>
            <div class="tip-text">{{ today.tips }}</div>
            <div class="actions">
              <el-button @click="goCustom">定制计划</el-button>
              <el-button type="primary" @click="goPlans">去挑一个计划</el-button>
            </div>
          </div>

          <!-- 态二：今天休息（★ 文案不要说成"没有计划"） -->
          <div v-else-if="today.status === TodayStatus.REST" class="today-card rest">
            <div class="big-text">今天没有安排训练</div>
            <div class="tip-text">{{ today.tips }}</div>
            <div class="rest-emoji">🛌</div>
            <!-- ★ A32：有计划在执行时也要能换一份。只剩"今天休息"四个字、没有任何出口，
                 用户唯一的办法是手敲 /plan/ai —— 第一个真实用户就是这么卡住的 -->
            <div class="actions">
              <el-button @click="goCustom">再定制一份</el-button>
            </div>
          </div>

          <!-- 态三：今天要练 -->
          <div v-else class="today-card train">
            <div class="row">
              <div>
                <div class="big-text">{{ today.dayName }}</div>
                <div class="tip-text">
                  第 {{ today.dayIndex }} 练 · {{ today.exerciseCount }} 个动作 · 预计
                  {{ today.estMinutes }} 分钟
                </div>
              </div>
              <el-tag v-if="today.sessionFinished" type="success" effect="plain">今天已练完</el-tag>
            </div>
            <div class="actions">
              <el-button v-if="today.sessionFinished" @click="startToday">查看今天的训练</el-button>
              <el-button v-else type="primary" size="large" @click="startToday">开始训练</el-button>
              <el-button @click="goCustom">再定制一份</el-button>
            </div>
          </div>
        </template>

        <!-- 推荐计划 -->
        <div id="recommend-section" class="section">
          <div class="section-head">
            <span class="section-title">推荐计划</span>
            <div class="goal-filter">
              <el-radio-group v-model="goalFilter" size="small" @change="reloadRecommend">
                <el-radio-button value="">全部</el-radio-button>
                <el-radio-button
                  v-for="g in dict?.goals ?? []"
                  :key="g"
                  :value="g"
                >
                  {{ g }}
                </el-radio-button>
              </el-radio-group>
              <el-button size="small" type="primary" plain @click="createEmpty">+ 创建计划</el-button>
            </div>
          </div>

          <el-skeleton v-if="loading" :rows="2" animated />
          <el-empty
            v-else-if="recommends.length === 0"
            description="还没有推荐计划"
            :image-size="70"
          />
          <div v-else class="plan-grid">
            <PlanCard
              v-for="p in recommends"
              :key="p.id"
              :plan="p"
              @open="openPlan(p.id)"
            />
          </div>
        </div>
      </el-tab-pane>

      <!-- ==================== 饮食（M11-3）==================== -->
      <!-- ★ 内容抽成 DietHome 子组件：它自己拉自己的接口，
           训练接口挂了不会把饮食 tab 一起拖成错误态（两个域刷新频率不同）。 -->
      <el-tab-pane label="饮食" :name="TAB_DIET">
        <DietHome :date="dietDate || undefined" />
      </el-tab-pane>

      <!-- ==================== 我的计划 ==================== -->
      <el-tab-pane label="我的计划" :name="TAB_MINE">
        <el-skeleton v-if="loading" :rows="3" animated />
        <el-empty
          v-else-if="mine.length === 0"
          description="还没有执行任何计划，去「训练」里挑一个"
          :image-size="70"
        />
        <div v-else class="mine-list">
          <div
            v-for="p in mineSorted"
            :key="p.id"
            class="mine-card"
            :class="{ 'is-abandoned': p.status === 2 }"
            :data-plan-id="p.id"
            @click="openPlan(p.id)"
          >
            <div class="mine-main">
              <div class="mine-title">{{ p.title }}</div>
              <div class="mine-sub">
                开始于 {{ p.startDate }} ·
                <el-tag v-if="p.status === 2" size="small" type="info" effect="plain">
                  {{ p.statusLabel }}
                </el-tag>
                <span v-else>{{ p.statusLabel }}</span>
              </div>
            </div>
            <div class="mine-side">
              <div class="mine-progress">
                <el-progress
                  :percentage="p.dayCount > 0 ? Math.round((p.finishedDays / p.dayCount) * 100) : 0"
                  :stroke-width="6"
                  :show-text="false"
                />
                <span class="progress-text">{{ p.progressText }}</span>
              </div>
              <!-- ★ A32：只有"进行中"才给停止入口。已完成 / 已放弃都没有可停的东西，
                   后端也会拒（已完成），但按钮根本不出现比"点了才报错"更好 -->
              <el-button
                v-if="p.status === 0"
                link
                type="danger"
                size="small"
                :loading="abandoningId === p.id"
                @click.stop="handleAbandon(p)"
              >
                停止执行
              </el-button>
            </div>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.plan-home {
  max-width: 1080px;
  margin: 0 auto;
}

.hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  border-radius: 14px;
  background: linear-gradient(135deg, #eef4ff 0%, #f7fbff 100%);
  margin-bottom: 12px;
}

.date {
  font-size: 18px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.hero-sub {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}

.plan-title {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.minutes {
  display: flex;
  align-items: center;
  gap: 12px;
}

.minute-item {
  text-align: center;
}

.minute-item .num {
  font-size: 24px;
  font-weight: 700;
  color: var(--el-color-primary);
  line-height: 1.1;
}

.minute-item .label {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.plan-tabs {
  margin-top: 4px;
}

.week-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 12px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  margin-bottom: 12px;
}

.week-days {
  display: flex;
  flex: 1;
  justify-content: space-around;
}

.week-day {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 4px 10px;
  border-radius: 10px;
}

.week-day.today {
  background: var(--el-color-primary);
}

.week-day.today .wd-label,
.week-day.today .wd-day {
  color: #fff;
}

.wd-label {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.wd-day {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.week-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  width: 52px;
  text-align: right;
}

.card-skeleton {
  padding: 16px;
  background: #fff;
  border-radius: 12px;
}

.today-card {
  border-radius: 14px;
  padding: 18px 20px;
  margin-bottom: 16px;
  background: #fff;
  border: 1px solid var(--el-border-color-light);
}

.today-card.train {
  border-left: 4px solid var(--el-color-primary);
}

.today-card.rest {
  border-left: 4px solid var(--el-color-info);
}

.today-card.muted {
  border-left: 4px solid var(--el-color-warning);
}

.big-text {
  font-size: 17px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.tip-text {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}

.rest-emoji {
  font-size: 26px;
  margin-top: 8px;
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.actions {
  display: flex;
  gap: 10px;
  margin-top: 14px;
}

.section {
  margin-top: 8px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
}

.goal-filter {
  display: flex;
  align-items: center;
  gap: 10px;
}

.plan-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
  gap: 12px;
}

.mine-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.mine-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  cursor: pointer;
}

.mine-card:hover {
  border-color: var(--el-color-primary-light-5);
}

/* 已放弃的计划沉底 + 压暗：它还在列表里（历史要留），但不该抢进行中计划的视觉重量 */
.mine-card.is-abandoned {
  opacity: 0.6;
}

.mine-card.is-abandoned .mine-title {
  font-weight: 500;
}

.mine-title {
  font-size: 15px;
  font-weight: 600;
}

.mine-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}

.mine-side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  flex-shrink: 0;
}

.mine-progress {
  width: 180px;
  flex-shrink: 0;
}

.progress-text {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
