<script setup lang="ts">
import { Refresh, Upload } from '@element-plus/icons-vue'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
// ★★ ECharts **按需引入**（M7-9）：原来是 `import * as echarts from 'echarts'`（全量），
//   把这一个 chunk 撑到 **1,125KB / gzip 374KB** —— 而本页**只画折线图**。
//   改成 core + 只 use 用得到的模块后，同样的图，体积降到约 1/4。
//
//   ⚠️ 按需引入的坑：**漏掉一个用到的模块不会编译报错**，表现是运行期静默失效
//   （图不画、或提示框/图例不弹）—— 所以下面每一项都对应页面里真实用到的配置：
//     line 系列 → LineChart · 坐标系与双 Y 轴 → GridComponent
//     tooltip → TooltipComponent · legend → LegendComponent · 渲染 → CanvasRenderer
import * as echarts from 'echarts/core'
import type { EChartsType } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

import { flushData, getCourseTop, getOverview, getTrend } from '@/api/data'
import type { CourseTopVO, OverviewVO, TrendVO } from '@/types/admin'
import { formatStatDate, formatThousands, formatYuan, toStatDate } from '@/utils/format'

/**
 * 数据看板
 *
 * ★★ 本页第一条设计原则：**必须显示 `source`**（realtime / archived / empty）。
 *
 *   同一个"今日成交额"，在一天里会变（还没落库，读的是 Redis 实时计数），
 *   落库之后就固定了。不把这件事说出来，用的人会经历：
 *   上午看是 1200、下午看是 2600、第二天再算账时对不上 ——
 *   然后开始怀疑整个系统。而实际上它一直是对的，只是**口径在变**。
 *
 * ★ 第二条：**概览卡片的每个数字都直接来自接口**，前端不做任何"环比/占比"计算。
 *   前端算出来的衍生指标一旦和后端口径不一致，就是"同一份数据两个数"，
 *   这类 bug 极难解释。要看环比，让后端加字段。
 */
const loading = ref(false)
const overview = ref<OverviewVO | null>(null)
const tops = ref<CourseTopVO[]>([])
const trend = ref<TrendVO | null>(null)
const trendDays = ref(7)
const statDate = ref<number | null>(null)
const flushing = ref(false)

const chartRef = ref<HTMLDivElement | null>(null)
// ★ 类型也跟着换：`echarts.ECharts` 是**全量包**的类型别名，从 'echarts/core' 走要用 `EChartsType`
let chart: EChartsType | null = null

/** `source` 的含义（要显式展示，不能只放在文档里） */
const sourceText = computed(() => {
  switch (overview.value?.source) {
    case 'realtime':
      return '实时计数（今天，还会变）'
    case 'archived':
      return '已落库（不再变）'
    case 'empty':
      return '无数据'
    default:
      return '—'
  }
})

const sourceTag = computed(() => {
  switch (overview.value?.source) {
    case 'realtime':
      return 'warning'
    case 'archived':
      return 'success'
    default:
      return 'info'
  }
})

/** 趋势区间里到底有没有数据（空数组时 ECharts 只会画一个空坐标系） */
const hasTrendData = computed(() => (trend.value?.dates ?? []).length > 0)

/** 概览卡片定义：全部是后端字段的直接映射，没有任何前端计算 */
const cards = computed(() => {
  const o = overview.value
  return [
    { key: 'orderCount', label: '成交订单', value: formatThousands(o?.orderCount), testid: 'ov-order-count' },
    { key: 'orderAmount', label: '成交额', value: formatYuan(o?.orderAmount), testid: 'ov-order-amount' },
    { key: 'refundCount', label: '退款笔数', value: formatThousands(o?.refundCount), testid: 'ov-refund-count' },
    { key: 'refundAmount', label: '退款额', value: formatYuan(o?.refundAmount), testid: 'ov-refund-amount' },
    { key: 'netAmount', label: '净成交额', value: formatYuan(o?.netAmount), testid: 'ov-net-amount', strong: true },
    { key: 'signInCount', label: '签到次数', value: formatThousands(o?.signInCount), testid: 'ov-signin' },
    { key: 'learnCount', label: '完成训练', value: formatThousands(o?.learnCount), testid: 'ov-learn' },
    { key: 'practiceCount', label: '小测答对', value: formatThousands(o?.practiceCount), testid: 'ov-practice' },
    { key: 'assessCount', label: '体测次数', value: formatThousands(o?.assessCount), testid: 'ov-assess' },
  ]
})

async function load() {
  loading.value = true
  try {
    const [ov, top, tr] = await Promise.all([
      getOverview(statDate.value ?? undefined),
      getCourseTop(statDate.value ?? undefined, 10),
      getTrend(trendDays.value),
    ])
    overview.value = ov
    tops.value = top ?? []
    trend.value = tr
    await nextTick()
    renderChart()
  } catch {
    /* request.ts 已提示 */
  } finally {
    loading.value = false
  }
}

/**
 * 渲染趋势图
 *
 * ★ 用 ECharts 的**双 Y 轴**：成交额（分→元，量级几百几千）和次数（个位数到几十）
 *   画在同一个轴上，次数那条线会被压成一条贴底的直线 —— 什么都看不出来。
 *   所以"金额"占左轴、"次数"占右轴，并**在 tooltip 里标清单位**。
 */
function renderChart() {
  if (!chartRef.value || !trend.value || !hasTrendData.value) return
  if (!chart) chart = echarts.init(chartRef.value)

  const t = trend.value
  const x = (t.dates ?? []).map((d) => formatStatDate(d).slice(5)) // 图上只显示 MM-DD
  const yuan = (t.orderAmounts ?? []).map((v) => Number(v) / 100)

  chart.setOption(
    {
      tooltip: {
        trigger: 'axis',
        // ★ 单位必须写清：金额是"元"，其余是"次/笔"
        valueFormatter: (v: unknown) => String(v),
      },
      legend: { data: ['成交额(元)', '成交订单', '签到', '完成训练', '体测'], bottom: 0 },
      grid: { left: 56, right: 56, top: 24, bottom: 48 },
      xAxis: { type: 'category', boundaryGap: false, data: x },
      yAxis: [
        { type: 'value', name: '元', axisLabel: { formatter: (v: number) => String(v) } },
        { type: 'value', name: '次', axisLabel: { formatter: (v: number) => String(v) } },
      ],
      series: [
        { name: '成交额(元)', type: 'line', smooth: true, yAxisIndex: 0, data: yuan, areaStyle: { opacity: 0.12 } },
        { name: '成交订单', type: 'line', smooth: true, yAxisIndex: 1, data: t.orderCounts ?? [] },
        { name: '签到', type: 'line', smooth: true, yAxisIndex: 1, data: t.signInCounts ?? [] },
        { name: '完成训练', type: 'line', smooth: true, yAxisIndex: 1, data: t.learnCounts ?? [] },
        { name: '体测', type: 'line', smooth: true, yAxisIndex: 1, data: t.assessCounts ?? [] },
      ],
    },
    true,
  )
}

/** 窗口尺寸变化时图要重算（否则容器变宽、图还是旧的宽度） */
function handleResize() {
  chart?.resize()
}

async function handleFlush() {
  flushing.value = true
  try {
    const d = statDate.value ?? toStatDate(new Date())
    await ElMessageBox.confirm(
      `把 ${formatStatDate(d)} 的实时计数写入汇总表？\n\n` +
        '· 落库后该日期的 source 会从 realtime 变成 archived（不再变）\n' +
        '· 后端是幂等的，重复调用不会重复累加',
      '手动落库',
      { type: 'info', confirmButtonText: '落库', cancelButtonText: '取消' },
    )
    const n = await flushData(d)
    ElMessage.success(`已落库（写入 ${n ?? 0} 行）`)
    await load()
  } catch {
    /* 取消 / 已提示 */
  } finally {
    flushing.value = false
  }
}

watch(trendDays, () => void load())

onMounted(async () => {
  await load()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  // ★ 不 dispose 的话，切换路由后实例还挂在已卸载的 DOM 上（内存泄漏 + 控制台警告）
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div v-loading="loading" class="fm-page dashboard">
    <!-- 头部：日期 + 来源 + 落库 -->
    <el-card shadow="never" class="head-card">
      <div class="head-row">
        <div class="head-left">
          <span class="head-title">运营概览</span>
          <span class="stat-date" data-testid="dash-stat-date">
            {{ statDate ? formatStatDate(statDate) : '今天' }}
          </span>
          <!-- ★ 数据来源标签：本页最不该省的一个元素 -->
          <el-tag :type="sourceTag" size="small" effect="dark" data-testid="dash-source">
            {{ sourceText }}
          </el-tag>
        </div>

        <div class="head-right">
          <el-date-picker
            v-model="statDate"
            type="date"
            value-format="YYYYMMDD"
            placeholder="选择日期（默认今天）"
            clearable
            style="width: 190px"
            data-testid="dash-date"
            @change="load"
          />
          <el-button :icon="Refresh" @click="load">刷新</el-button>
          <el-button type="primary" plain :icon="Upload" :loading="flushing" data-testid="dash-flush" @click="handleFlush">
            手动落库
          </el-button>
        </div>
      </div>

      <div class="hint-line">
        ★ 「实时计数」= 当天数据还在 Redis 里，**还会随业务变化**；
        「已落库」= 已写入汇总表，不会再变。两者都是正确值，只是**口径不同** ——
        看板必须把这句说出来，否则"上午 1200、下午 2600"会被当成 bug。
      </div>
    </el-card>

    <!-- 概览卡片 -->
    <div class="ov-grid">
      <el-card
        v-for="c in cards"
        :key="c.key"
        shadow="never"
        class="ov-card"
        :class="{ 'is-strong': c.strong }"
      >
        <div class="ov-label">{{ c.label }}</div>
        <div class="ov-value" :data-testid="c.testid">{{ c.value }}</div>
      </el-card>
    </div>

    <!-- 趋势 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span class="fm-card-title">趋势</span>
          <el-radio-group v-model="trendDays" size="small" data-testid="dash-trend-days">
            <el-radio-button :value="7">7 天</el-radio-button>
            <el-radio-button :value="14">14 天</el-radio-button>
            <el-radio-button :value="30">30 天</el-radio-button>
          </el-radio-group>
        </div>
      </template>
      <div v-show="hasTrendData" ref="chartRef" class="chart" data-testid="dash-chart" />
      <!--
        ★ 空态：没有数据时 ECharts 会画一个**空坐标系**（只有轴线、没有任何线），
          看起来"图坏了"。而"最近 N 天没有成交/签到"是**正常状态**（新环境就是这样），
          所以要说出来，而不是留一片空白让人猜。
        ★ 用 `v-show` 而不是 `v-if` 隐藏图表容器：容器一旦从 DOM 里消失，
          ECharts 实例就"挂"在一个脱离文档的元素上（再显示时不 resize 会错位）。
          `v-show` 只是 display:none，实例仍然有效；等有数据时再 render 即可。
      -->
      <el-empty
        v-if="!hasTrendData"
        class="chart-empty"
        description="该区间没有统计数据（新环境或尚未产生行为时属正常）"
        :image-size="60"
        data-testid="dash-chart-empty"
      />
    </el-card>

    <!-- 销量榜 -->
    <el-card shadow="never">
      <template #header>
        <div class="fm-card-title">课程销量榜</div>
      </template>
      <el-table :data="tops" stripe data-testid="dash-top-table" empty-text="该日期没有成交数据">
        <el-table-column prop="rank" label="排名" width="70" />
        <el-table-column prop="courseId" label="课程 id" width="120" />
        <el-table-column label="销量" width="110">
          <template #default="{ row }">{{ formatThousands(row.orderCount) }}</template>
        </el-table-column>
        <el-table-column label="销售额" width="140">
          <template #default="{ row }">{{ formatYuan(row.orderAmount) }}</template>
        </el-table-column>
        <el-table-column label="退款笔数" width="110">
          <template #default="{ row }">{{ formatThousands(row.refundCount) }}</template>
        </el-table-column>
        <el-table-column label="数据来源" min-width="120">
          <template #default="{ row }">
            <el-tag :type="row.source === 'archived' ? 'success' : 'warning'" size="small" effect="plain">
              {{ row.source === 'archived' ? '已落库' : '实时' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}

.head-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.head-title {
  font-size: 16px;
  font-weight: 600;
}

.stat-date {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.head-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.hint-line {
  margin-top: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.8;
}

.ov-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 12px;
}

.ov-card {
  border-radius: var(--fm-radius);
}

.ov-card.is-strong {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
}

.ov-label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.ov-value {
  margin-top: 6px;
  font-size: 22px;
  font-weight: 700;
  line-height: 1.2;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.chart {
  width: 100%;
  height: 320px;
}

.chart-empty {
  padding: 40px 0;
}
</style>
