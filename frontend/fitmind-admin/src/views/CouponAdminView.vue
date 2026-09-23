<script setup lang="ts">
import { Check, Refresh, Star, Tickets } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'

import { beginIssueCoupon, getMyCoupons, receiveCoupon, saveCoupon } from '@/api/promotion'
import type { Coupon } from '@/types/admin'
import { COUPON_STATUS, DISCOUNT_TYPES, OBTAIN_WAYS } from '@/types/admin'
import { formatDateTime, formatYuan } from '@/utils/format'

/**
 * 优惠券（建券 + 发放）
 *
 * ★★ 三个"不这么做券就用不了"的硬约束（都在后端代码里核实过，见 `api/promotion.ts` 文件头）：
 *
 *  ① **`issueEndTime` 必填**：`cacheCoupon` 里对它直接 `.atZone(...)`，
 *     没有判空 → 漏了它建券直接 500（不是友好提示）。所以表单把它标成必填。
 *  ② **建完必须"发放"**：`receiveCoupon` 第一件事就校验 `status == 2(发放中)`，
 *     只创建不发放 = 一张谁都领不到的券。
 *  ③ **有效期用 `termDays`**：`termDays > 0` 时走"领取后 N 天"
 *     （`now.plusDays(termDays)`），比固定日期更适合演示（不会因为"建早了"而过期）。
 *
 * ⚠️ **后端没有"券列表"查询接口**（`CouponController` 只有 POST 与 PUT issue），
 *   所以本页**不假装有列表**：创建后展示"刚建的这张"，并提供"自己领一张"，
 *   用 `GET /prs/user-coupons`（我的券）证明它真的可用。
 *   这也是唯一诚实的做法 —— 编一个前端本地列表会让人以为后端有这能力。
 */
const submitting = ref(false)
const issuing = ref(false)
const receiving = ref(false)
const created = ref<Coupon | null>(null)
const myCoupons = ref<Record<string, unknown>[]>([])
const myCouponsLoading = ref(false)

/**
 * 表单模型
 *
 * ★★ 这里刻意**不用 `Coupon` 类型**，而是单独定义一套"界面口径"：
 *   **界面上的钱一律填「元」，提交时才统一 ×100 转成「分」**。
 *
 *   第一版我直接复用了 `Coupon`（字段是分）去绑 `el-input-number`，
 *   结果 label 写着"优惠金额（元）"、绑的却是分 —— 用户填 20 以为是 20 元，
 *   实际是 2 毛。这类"单位错位"不报错、不崩溃，只是金额静默错 100 倍，
 *   属于最典型的"看起来对"。
 *   → 界面模型与接口模型**显式隔开**，转换只在一处发生（`buildPayload`）。
 */
const form = reactive({
  name: '新人体验券（满 100 减 20）',
  discountType: 1,
  specific: 0,
  /** 元（满减 / 每满减 / 无门槛时用） */
  discountYuan: 20,
  /** 元（0 = 无门槛） */
  thresholdYuan: 100,
  /** 元（折扣券的封顶；0 = 不封顶） */
  maxDiscountYuan: 0,
  obtainWay: 1,
  totalNum: 100,
  userLimit: 1,
  termDays: 30,
})

/** 发行时间默认给"现在 ~ 7 天后"（`issueEndTime` 必填，不能留空） */
const issueRange = ref<[string, string] | null>(null)

const typeHint = computed(() => DISCOUNT_TYPES.find((t) => t.value === form.discountType)?.hint ?? '')
const isRate = computed(() => form.discountType === 3)

/**
 * 刚创建那张券的规则文案
 *
 * ★ 为什么抽成 computed 而不是写在模板里：
 *   模板里那种 `DISCOUNT_TYPES.find((t) => t.value === created.discountType)`
 *   —— 在**箭头函数**里访问 `created` 时，TS 无法沿用 `v-if="created"` 的窄化结论
 *   （它认为回调执行到那一刻 `created` 可能已经是 null），于是报 TS18047。
 *   抽成 computed 顺带把这段展示逻辑从模板里挪出来，可读性也更好。
 */
const createdRule = computed(() => {
  const c = created.value
  if (!c) return ''
  const label = DISCOUNT_TYPES.find((t) => t.value === c.discountType)?.label ?? ''
  const value = c.discountType === 3 ? `${c.discountValue / 10} 折` : formatYuan(c.discountValue)
  const threshold = c.thresholdAmount > 0 ? `（满 ${formatYuan(c.thresholdAmount)}）` : ''
  return `${label} · ${value}${threshold}`
})

/** 折扣券的输入是"折"（8 折），提交时要转成后端口径（80） */
const rateInput = ref(8)

/** 元 → 分：★ 全页唯一的单位转换点（`Math.round` 防浮点误差：20.1 * 100 = 2009.9999…） */
function yuan2fen(y: number | null | undefined): number {
  if (y === null || y === undefined) return 0
  return Math.round(y * 100)
}

/** 把"界面模型"翻译成后端要的 `Coupon`（后端字段单位是分） */
function buildPayload(): Coupon {
  return {
    id: null,
    name: form.name.trim(),
    discountType: form.discountType,
    specific: form.specific,
    // ★ 折扣券：界面填"8 折"，后端要 80（`RateDiscount` 算 `100 - discountValue`）
    discountValue: isRate.value ? Math.round(rateInput.value * 10) : yuan2fen(form.discountYuan),
    thresholdAmount: yuan2fen(form.thresholdYuan),
    // 折扣券必须封顶，否则大额订单会亏（后端 0/空 = 不封顶）
    maxDiscountAmount: isRate.value && form.maxDiscountYuan > 0 ? yuan2fen(form.maxDiscountYuan) : null,
    obtainWay: form.obtainWay,
    status: null,
    totalNum: form.totalNum,
    issueBeginTime: issueRange.value?.[0] ?? null,
    // ★★ 必填：后端 `cacheCoupon` 对它直接解引用（不判空）→ 留空会让建券 500
    issueEndTime: issueRange.value?.[1] ?? null,
    userLimit: form.userLimit,
    // 用 termDays 口径时显式清掉固定区间，避免"两个都填"时后端取哪个不明确
    termDays: form.termDays,
    termBeginTime: null,
    termEndTime: null,
    creater: null,
    createTime: null,
    // 不限范围时给空数组（而不是 null），避免后端判空分支不一致
    scopes: [],
  }
}

async function handleSubmit() {
  if (!form.name?.trim()) {
    ElMessage.warning('请填写券名称')
    return
  }
  if (!issueRange.value?.[1]) {
    ElMessage.warning('请选择发放结束时间（后端对它有直接依赖，不能为空）')
    return
  }
  if (form.specific === 1) {
    ElMessage.warning('「限定课程」还需要填课程 id（本页未做课程选择器）→ 请先用「不限」')
    return
  }

  submitting.value = true
  try {
    const payload = buildPayload()
    const id = await saveCoupon(payload)
    created.value = { ...payload, id: String(id), status: COUPON_STATUS[0].value }
    ElMessage.success(`券已创建（id=${id}）。★ 还需要「开始发放」，否则谁都领不到`)
  } catch {
    /* 已提示 */
  } finally {
    submitting.value = false
  }
}

async function handleIssue() {
  if (!created.value?.id) return
  issuing.value = true
  try {
    await beginIssueCoupon(created.value.id)
    created.value = { ...created.value, status: 2 }
    ElMessage.success('已开始发放（状态 → 发放中）')
  } catch {
    /* 已提示 */
  } finally {
    issuing.value = false
  }
}

async function loadMyCoupons() {
  myCouponsLoading.value = true
  try {
    myCoupons.value = (await getMyCoupons()) ?? []
  } catch {
    /* 已提示 */
  } finally {
    myCouponsLoading.value = false
  }
}

async function handleReceive() {
  if (!created.value?.id) return
  receiving.value = true
  try {
    await receiveCoupon(created.value.id)
    ElMessage.success('已领取一张（可在下方"我的券"里看到）')
    await loadMyCoupons()
  } catch {
    /* 已提示（比如"券还未开始发放"、"超出每人限领"） */
  } finally {
    receiving.value = false
  }
}

function quickFill(preset: 'threshold' | 'rate' | 'none') {
  if (preset === 'threshold') {
    Object.assign(form, {
      name: '满 100 减 20',
      discountType: 1,
      discountYuan: 20,
      thresholdYuan: 100,
      maxDiscountYuan: 0,
    })
  } else if (preset === 'rate') {
    Object.assign(form, {
      name: '满 100 打 8 折（最高减 50）',
      discountType: 3,
      thresholdYuan: 100,
      maxDiscountYuan: 50,
    })
    rateInput.value = 8
  } else {
    Object.assign(form, {
      name: '无门槛 5 元',
      discountType: 4,
      discountYuan: 5,
      thresholdYuan: 0,
      maxDiscountYuan: 0,
    })
  }
}

onMounted(() => {
  // 默认发放窗口：现在 → 7 天后（★ `issueEndTime` 不能为空，所以给好默认值而不是留空）
  const now = new Date()
  const end = new Date(now.getTime() + 7 * 24 * 3600 * 1000)
  const fmt = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ` +
    `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:00`
  issueRange.value = [fmt(now), fmt(end)]
  void loadMyCoupons()
})
</script>

<template>
  <div class="fm-page coupon-admin">
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span class="fm-card-title">新建优惠券</span>
          <div class="presets">
            <span class="sub">快速填充：</span>
            <el-button size="small" @click="quickFill('threshold')">满减</el-button>
            <el-button size="small" @click="quickFill('rate')">折扣</el-button>
            <el-button size="small" @click="quickFill('none')">无门槛</el-button>
          </div>
        </div>
      </template>

      <el-form label-width="150px" label-position="right" class="coupon-form">
        <el-form-item label="券名称" required>
          <el-input v-model="form.name" data-testid="coupon-name" placeholder="如：新人体验券" />
        </el-form-item>

        <el-form-item label="折扣类型" required>
          <el-radio-group v-model="form.discountType" data-testid="coupon-type">
            <el-radio-button v-for="t in DISCOUNT_TYPES" :key="t.value" :value="t.value">
              {{ t.label }}
            </el-radio-button>
          </el-radio-group>
          <span class="field-hint">{{ typeHint }}</span>
        </el-form-item>

        <el-form-item v-if="!isRate" label="优惠金额（元）" required>
          <el-input-number
            v-model="form.discountYuan"
            data-testid="coupon-value"
            :min="1"
            :step="10"
            :precision="2"
            :controls="false"
            style="width: 180px"
          />
          <span class="field-hint">
            单位是**元**（提交时自动 ×100 转为后端的"分"，当前 = {{ formatYuan(yuan2fen(form.discountYuan)) }}）
          </span>
        </el-form-item>

        <el-form-item v-else label="折扣（几折）" required>
          <el-input-number
            v-model="rateInput"
            data-testid="coupon-rate"
            :min="1"
            :max="9.9"
            :step="0.5"
            :precision="1"
            :controls="false"
            style="width: 140px"
          />
          <span class="field-hint">
            ★ 后端口径是 `discountValue`（8 折 = 80），这里填"8"会自动换算
          </span>
        </el-form-item>

        <el-form-item label="使用门槛（元）">
          <el-input-number
            v-model="form.thresholdYuan"
            data-testid="coupon-threshold"
            :min="0"
            :step="10"
            :precision="2"
            :controls="false"
            style="width: 180px"
          />
          <span class="field-hint">单位是元；0 = 无门槛（折扣券也用它做"满多少才能用"）</span>
        </el-form-item>

        <el-form-item v-if="isRate" label="最高优惠（元）">
          <el-input-number
            v-model="form.maxDiscountYuan"
            :min="0"
            :step="10"
            :precision="2"
            :controls="false"
            style="width: 180px"
          />
          <span class="field-hint">单位是元；折扣券**必须封顶**（0 = 不封顶，不推荐）</span>
        </el-form-item>

        <el-form-item label="发行总量 / 每人限领">
          <el-input-number v-model="form.totalNum" :min="1" :max="1000000" :controls="false" style="width: 140px" data-testid="coupon-total" />
          <span class="gap" />
          <el-input-number v-model="form.userLimit" :min="1" :max="100" :controls="false" style="width: 100px" data-testid="coupon-user-limit" />
        </el-form-item>

        <el-form-item label="发放时间（★ 结束时间必填）" required>
          <el-date-picker
            v-model="issueRange"
            type="datetimerange"
            value-format="YYYY-MM-DD HH:mm:ss"
            range-separator="→"
            start-placeholder="开始"
            end-placeholder="结束"
            data-testid="coupon-issue-range"
          />
          <span class="field-hint">
            ★★ 必填：后端缓存券信息时**直接解引用它**，留空会让建券接口 500（不是友好提示）
          </span>
        </el-form-item>

        <el-form-item label="有效期">
          <el-radio-group v-model="form.termDays">
            <el-radio-button :value="7">领取后 7 天</el-radio-button>
            <el-radio-button :value="30">30 天</el-radio-button>
            <el-radio-button :value="90">90 天</el-radio-button>
          </el-radio-group>
          <span class="field-hint">
            用"领取后 N 天"而不是固定日期 —— 演示环境不会因为"券建早了"而过期
          </span>
        </el-form-item>

        <el-form-item label="领取方式">
          <el-radio-group v-model="form.obtainWay" data-testid="coupon-obtain-way">
            <el-radio-button v-for="w in OBTAIN_WAYS" :key="w.value" :value="w.value">{{ w.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="适用范围">
          <el-radio-group v-model="form.specific">
            <el-radio-button :value="0">不限</el-radio-button>
            <el-radio-button :value="1">限定课程</el-radio-button>
          </el-radio-group>
          <span class="field-hint">限定范围要填课程 id（`scopes`），演示用「不限」即可</span>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :icon="Tickets" :loading="submitting" data-testid="coupon-submit" @click="handleSubmit">
            创建优惠券
          </el-button>
        </el-form-item>
      </el-form>

      <div class="hint-line">
        ★★ 建券是**两步**：创建（状态=待发放）→ **开始发放**（状态=发放中）。
        领券接口第一件事就是校验"是否发放中"，所以**只创建不发放 = 谁都领不到的券**。
      </div>
    </el-card>

    <!-- 刚创建的券 -->
    <el-card v-if="created" shadow="never" data-testid="coupon-created-card">
      <template #header>
        <div class="card-head">
          <span class="fm-card-title">刚创建的券</span>
          <el-tag type="success" size="small">id = {{ created.id }}</el-tag>
        </div>
      </template>

      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="名称">{{ created.name }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          {{ created.status === 2 ? '发放中' : '待发放' }}
        </el-descriptions-item>
        <el-descriptions-item label="规则">{{ createdRule }}</el-descriptions-item>
        <el-descriptions-item label="发行 / 每人">
          {{ created.totalNum }} / {{ created.userLimit }}
        </el-descriptions-item>
        <el-descriptions-item label="发放截止">{{ formatDateTime(created.issueEndTime) }}</el-descriptions-item>
        <el-descriptions-item label="有效期">领取后 {{ created.termDays }} 天</el-descriptions-item>
      </el-descriptions>

      <div class="created-actions">
        <el-button
          v-if="created.status !== 2"
          type="warning"
          :icon="Star"
          :loading="issuing"
          data-testid="coupon-issue"
          @click="handleIssue"
        >
          开始发放（不发这步谁都领不到）
        </el-button>
        <el-button
          v-else
          type="primary"
          :icon="Check"
          :loading="receiving"
          data-testid="coupon-receive"
          @click="handleReceive"
        >
          自己领一张（验证券可用）
        </el-button>
      </div>
    </el-card>

    <!-- 我的券：证明券真的可用 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span class="fm-card-title">我的券（管理员自己领的）</span>
          <el-button :icon="Refresh" size="small" @click="loadMyCoupons">刷新</el-button>
        </div>
      </template>

      <el-table v-loading="myCouponsLoading" :data="myCoupons" size="small" data-testid="coupon-mine-table" empty-text="还没有领取过券">
        <el-table-column prop="name" label="名称" min-width="200" />
        <el-table-column label="规则" min-width="180">
          <template #default="{ row }">{{ row.rule ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '未使用' : row.status === 2 ? '已使用' : '已失效' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="有效期至" width="160">
          <template #default="{ row }">{{ formatDateTime(row.termEndTime) }}</template>
        </el-table-column>
      </el-table>

      <div class="hint-line">
        ★ 后端**没有"券列表"查询接口**（`CouponController` 只有创建与发放），
        所以上面看到的是「**当前登录用户**已领取的券」——
        用它来证明"这张券确实能领、规则解析正确"。
        <br />
        要验证"下单时能用"，去用户端结算页（那里读的是同一个 `GET /prs/user-coupons`）。
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.coupon-admin {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
}

.presets {
  display: flex;
  align-items: center;
  gap: 6px;
}

.coupon-form {
  max-width: 760px;
}

.field-hint {
  margin-left: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.7;
}

.gap {
  display: inline-block;
  width: 10px;
}

.hint-line {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.8;
}

.created-actions {
  margin-top: 14px;
  display: flex;
  gap: 10px;
}
</style>
