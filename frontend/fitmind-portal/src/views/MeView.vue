<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import {
  Bell,
  Calendar,
  ChatDotRound,
  Edit,
  List,
  Medal,
  Reading,
  RefreshLeft,
  ShoppingCart,
  Ticket,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules, UploadFile } from 'element-plus'
import { useRouter } from 'vue-router'

import { deleteFile, uploadImage } from '@/api/media'
import { getBodyProfile, saveBodyProfile, updateCurrentUser } from '@/api/user'
import { useAuthStore } from '@/stores/auth'
import type { BodyProfile, BodyProfileForm, UpdateProfileForm } from '@/types/user'
import { GOAL_TYPE_OPTIONS, TRAINING_LEVEL_OPTIONS, WEEKLY_FREQUENCY_OPTIONS } from '@/types/user'
import { formatDateTime } from '@/utils/format'
import { ApiError } from '@/utils/request'

/**
 * 我的（个人中心）
 *
 * M7-1 只做"证明登录态与用户信息链路是通的"这件事：
 * 页面上的每个字段都直接来自 `GET /us/users/me`，没有任何前端硬编码，
 * 所以它天然就是验收脚本的交叉验证对象（页面值必须 == 接口值）。
 *
 * M7-12 补上两块（都是 M7 方案 §7.1 第 18 页承诺过的）：
 *   ① **编辑资料**（含头像上传）→ `PUT /us/users/me` + `POST /ms/files`
 *   ② **身体档案** → `GET/PUT /us/users/me/profile`
 */
const auth = useAuthStore()
const router = useRouter()

/** 快捷入口：学习（M7-4）与交易（M7-3）都收在这里 */
const quickLinks = [
  { path: '/my-courses', label: '我的课程', desc: '继续训练', icon: Reading, testid: 'me-link-courses' },
  { path: '/plans', label: '训练计划', desc: '每周频率', icon: Calendar, testid: 'me-link-plans' },
  { path: '/community', label: '社区', desc: '问答与笔记', icon: ChatDotRound, testid: 'me-link-community' },
  { path: '/messages', label: '消息中心', desc: '通知与提醒', icon: Bell, testid: 'me-link-messages' },
  { path: '/growth', label: '成长中心', desc: '签到与积分', icon: Medal, testid: 'me-link-growth' },
  { path: '/my-coupons', label: '我的优惠券', desc: '券与兑换码', icon: Ticket, testid: 'me-link-coupons' },
  { path: '/cart', label: '购物车', desc: '待结算的课程', icon: ShoppingCart, testid: 'me-link-cart' },
  { path: '/orders', label: '我的订单', desc: '待支付 / 已报名', icon: List, testid: 'me-link-orders' },
  { path: '/refunds', label: '我的退款', desc: '查看退款进度', icon: RefreshLeft, testid: 'me-link-refunds' },
]

onMounted(async () => {
  if (auth.isLogin && !auth.user) {
    try {
      await auth.fetchMe()
    } catch {
      /* 已由 request.ts 统一处理 */
    }
  }
  await loadProfile()
})

const fields = computed(() => {
  const u = auth.user
  if (!u) return []
  return [
    { label: '用户 ID', value: u.id },
    { label: '手机号', value: u.cellPhone },
    { label: '姓名 / 昵称', value: u.name },
    { label: '用户名', value: u.username },
    { label: '角色', value: auth.roleLabel },
    { label: '性别', value: genderText(u.gender) },
    { label: '邮箱', value: u.email },
    { label: 'QQ', value: u.qq },
    { label: '生日', value: u.birthday },
    { label: '所在地区', value: regionText(u.province, u.city, u.district) },
    { label: '账号状态', value: u.status === 1 ? '正常' : u.status === 0 ? '已禁用' : null },
    { label: '注册时间', value: u.createTime },
    { label: '健身宣言', value: u.intro },
  ]
})

function genderText(gender: number | null): string | null {
  if (gender === 0) return '男'
  if (gender === 1) return '女'
  return null
}

function regionText(...parts: (string | null)[]): string | null {
  const joined = parts.filter((p) => p).join(' ')
  return joined || null
}

/** 后端未填字段一律返回 null，界面上统一显示为 — 而不是空白（空白会被误认为页面坏了） */
function display(value: unknown): string {
  return value === null || value === undefined || value === '' ? '—' : String(value)
}

/** 头像降级：没有 icon 时用昵称首字（与全站"封面破图走兜底"同一姿态） */
const avatarText = computed(() => (auth.displayName || 'U').slice(0, 1).toUpperCase())

// ============================================================
// ① 编辑资料（+ 改密码）
// ============================================================

const editVisible = ref(false)
const editFormRef = ref<FormInstance>()
const saving = ref(false)
const uploading = ref(false)
const uploadPercent = ref(0)

/**
 * 编辑表单。
 *
 * ★ 为什么"改密码"和"改资料"放在同一个弹窗里：**后端就是同一个接口**（`PUT /us/users/me`，
 *   表单是 `UserFormDTO`，`password`/`oldPassword` 就在同一个 DTO 里）。
 *   拆成两个弹窗会让人以为是两条链路，反而更容易漏传字段。
 */
const editForm = reactive<UpdateProfileForm & { newPassword: string }>({
  name: '',
  gender: null,
  icon: null,
  email: '',
  qq: '',
  birthday: null,
  province: '',
  city: '',
  district: '',
  intro: '',
  oldPassword: '',
  newPassword: '',
})

/**
 * 本次编辑里"刚上传但尚未保存"的头像 id。
 *
 * ★ 为什么要记它：头像换了一张再换一张，前一张就成了**孤儿图片**（占着桶还没人引用）。
 *   这里在"换成新的"时顺手删掉上一张 —— 但**只删本次会话自己传的**，理由是
 *   `DELETE /files/{id}` 后端**没有归属校验**（已登记 `#A14`），
 *   所以绝不能让这个删除能力作用到"任意 id"上。
 */
let pendingIconFileId: string | null = null

function validateNewPassword(_rule: unknown, value: string, callback: (e?: Error) => void) {
  if (!value) return callback()
  if (value.length < 6) return callback(new Error('新密码至少 6 位'))
  if (!editForm.oldPassword) return callback(new Error('修改密码时必须填写原密码'))
  return callback()
}

/**
 * 性别需要一个"适配层"。
 *
 * ★ 后端语义：`gender` 未填是 `null`（不是 undefined）。
 *   而 Element Plus 的 `el-radio-group` 的 v-model 类型不含 `null`
 *   （它只接受 string | number | boolean | undefined）。
 *   直接把 `number | null` 绑上去，类型检查会报 TS2322。
 *   → 与其把模型类型改成 undefined（那提交时又要把"未选择"翻译回 null，
 *     容易漏掉导致把 gender 传成 undefined 而后端当成"不修改"），
 *     不如在这里做一次显式映射，让 null 语义始终留在数据层。
 */
const genderModel = computed<number | undefined>({
  get: () => editForm.gender ?? undefined,
  set: (v) => {
    editForm.gender = v ?? null
  },
})

const editRules: FormRules = {
  name: [{ max: 20, message: '昵称不超过 20 个字', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  newPassword: [{ validator: validateNewPassword, trigger: 'blur' }],
}

function openEdit() {
  const u = auth.user
  editForm.name = u?.name ?? ''
  editForm.gender = u?.gender ?? null
  editForm.icon = u?.icon ?? null
  editForm.email = u?.email ?? ''
  editForm.qq = u?.qq ?? ''
  editForm.birthday = u?.birthday ?? null
  editForm.province = u?.province ?? ''
  editForm.city = u?.city ?? ''
  editForm.district = u?.district ?? ''
  editForm.intro = u?.intro ?? ''
  editForm.oldPassword = ''
  editForm.newPassword = ''
  pendingIconFileId = null
  uploadPercent.value = 0
  editVisible.value = true
}

/**
 * 上传头像。
 *
 * ★ 走 `POST /ms/files?bizType=avatar`（服务端中转的 multipart），**不是**预签名直传：
 *   · `BizType.AVATAR` 的目录前缀是 `avatar/`，而 `avatar/` 在 M8-1 的**匿名读白名单**里
 *     → 存进 `icon` 之后 `<img>` 能直接显示（`<img>` 不能带 Authorization 头）
 *   · 存的是 `rawUrl`（不带签名）而不是 `url`（带签名、有有效期）——
 *     存后者会"过一会儿变破图"，这是 M7 §12.6 第 4 条拒绝预签名的同一个理由
 */
async function handleAvatarChange(file: UploadFile) {
  const raw = file.raw
  if (!raw) return
  if (!raw.type.startsWith('image/')) {
    ElMessage.warning('头像必须是图片文件')
    return
  }
  if (raw.size > 2 * 1024 * 1024) {
    ElMessage.warning('头像不要超过 2MB')
    return
  }

  uploading.value = true
  uploadPercent.value = 0
  try {
    const uploaded = await uploadImage(raw, 'avatar', (p) => (uploadPercent.value = p))
    editForm.icon = uploaded.rawUrl
    // 换新图成功后再删上一张（顺序不能反：先删后传，一旦上传失败就没头像了）
    if (pendingIconFileId && pendingIconFileId !== uploaded.id) {
      // 删失败不影响主流程 —— 孤儿图是"浪费"，不是"错误"
      deleteFile(pendingIconFileId).catch(() => undefined)
    }
    pendingIconFileId = uploaded.id
    ElMessage.success('头像已上传，保存后生效')
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '头像上传失败')
  } finally {
    uploading.value = false
  }
}

async function submitEdit() {
  const valid = await editFormRef.value
    ?.validate()
    .then(() => true)
    .catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    const payload: UpdateProfileForm = {
      name: editForm.name || null,
      gender: editForm.gender,
      icon: editForm.icon || null,
      email: editForm.email || null,
      qq: editForm.qq || null,
      birthday: editForm.birthday || null,
      province: editForm.province || null,
      city: editForm.city || null,
      district: editForm.district || null,
      intro: editForm.intro || null,
    }
    if (editForm.newPassword) {
      payload.oldPassword = editForm.oldPassword
      payload.password = editForm.newPassword
    }
    await updateCurrentUser(payload)
    // ★ 必须重新拉一次 /us/users/me：本页所有字段都来自它，不刷新就还是旧值
    await auth.fetchMe()
    ElMessage.success(editForm.newPassword ? '资料与密码已更新' : '资料已更新')
    editVisible.value = false
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

// ============================================================
// ② 身体档案
// ============================================================

const profile = ref<BodyProfile | null>(null)
const profileLoading = ref(false)
/** 后端在"未填写"时返回 data: null，这与"请求失败"是两件事，要分开表达 */
const profileError = ref(false)

const profileVisible = ref(false)
const profileSaving = ref(false)

const profileForm = reactive<BodyProfileForm>({
  height: null,
  weight: null,
  targetWeight: null,
  bodyFatRate: null,
  waist: null,
  chest: null,
  arm: null,
  hip: null,
  goalType: null,
  trainingLevel: null,
  weeklyFrequency: null,
  injuryHistory: '',
})

async function loadProfile() {
  // 未登录时不必查（未登录会 401，没必要制造一条噪音请求）
  if (!auth.isLogin) return
  profileLoading.value = true
  profileError.value = false
  try {
    profile.value = await getBodyProfile()
  } catch {
    profile.value = null
    profileError.value = true
  } finally {
    profileLoading.value = false
  }
}

function openProfileEdit() {
  const p = profile.value
  profileForm.height = p?.height ?? null
  profileForm.weight = p?.weight ?? null
  profileForm.targetWeight = p?.targetWeight ?? null
  profileForm.bodyFatRate = p?.bodyFatRate ?? null
  profileForm.waist = p?.waist ?? null
  profileForm.chest = p?.chest ?? null
  profileForm.arm = p?.arm ?? null
  profileForm.hip = p?.hip ?? null
  profileForm.goalType = p?.goalType ?? null
  profileForm.trainingLevel = p?.trainingLevel ?? null
  profileForm.weeklyFrequency = p?.weeklyFrequency ?? null
  profileForm.injuryHistory = p?.injuryHistory ?? ''
  profileVisible.value = true
}

async function submitProfile() {
  profileSaving.value = true
  try {
    // ⚠️ 不传 bmi —— 后端按身高体重自己算（传了会被忽略，见 types/user.ts 的说明）
    await saveBodyProfile({
      ...profileForm,
      injuryHistory: profileForm.injuryHistory || null,
    })
    ElMessage.success('身体档案已保存')
    profileVisible.value = false
    await loadProfile()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '保存失败')
  } finally {
    profileSaving.value = false
  }
}

/** 数值字段展示（后端可能返回 null；空值统一显示 —） */
function num(value: number | null | undefined, unit = ''): string {
  return value === null || value === undefined ? '—' : `${value}${unit}`
}
</script>

<template>
  <div class="fm-page">
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <div class="fm-card-title">个人资料</div>
          <el-button
            v-if="auth.isLogin"
            data-testid="me-edit-open"
            type="primary"
            size="small"
            :icon="Edit"
            @click="openEdit"
          >
            编辑资料
          </el-button>
        </div>
      </template>

      <el-skeleton v-if="auth.isLogin && !auth.user" :rows="6" animated />

      <div v-else class="profile-wrap">
        <div v-if="auth.isLogin && auth.user" class="avatar-block">
          <el-avatar :size="72" :src="auth.user.icon || undefined" data-testid="me-avatar">
            {{ avatarText }}
          </el-avatar>
          <div class="avatar-meta">
            <div class="avatar-name" data-testid="me-display-name">{{ auth.displayName || '—' }}</div>
            <div class="avatar-role">{{ auth.roleLabel }}</div>
          </div>
        </div>

        <el-descriptions v-if="auth.isLogin" data-testid="me-profile" :column="2" border>
          <el-descriptions-item v-for="field in fields" :key="field.label" :label="field.label">
            {{ display(field.value) }}
          </el-descriptions-item>
        </el-descriptions>
      </div>

      <el-empty v-if="!auth.isLogin" description="未登录" />
    </el-card>

    <!-- ==================== 身体档案（M7-12）==================== -->
    <el-card shadow="never" class="trade-card">
      <template #header>
        <div class="card-head">
          <div class="fm-card-title">身体档案</div>
          <div v-if="auth.isLogin" class="head-actions">
            <el-button
              data-testid="body-profile-edit"
              size="small"
              :icon="Edit"
              @click="openProfileEdit"
            >
              {{ profile ? '更新档案' : '填写档案' }}
            </el-button>
          </div>
        </div>
      </template>

      <el-skeleton v-if="profileLoading" :rows="4" animated />

      <el-alert
        v-else-if="profileError"
        type="error"
        title="身体档案加载失败"
        description="请确认 fm-user 服务已启动，然后刷新页面重试。"
        :closable="false"
        show-icon
      />

      <el-empty
        v-else-if="!profile"
        data-testid="body-profile-empty"
        description="还没有身体档案 —— 填一份，AI 教练才能按你的目标推荐课程"
      >
        <el-button type="primary" data-testid="body-profile-create" @click="openProfileEdit">
          填写身体档案
        </el-button>
      </el-empty>

      <div v-else class="body-profile" data-testid="body-profile">
        <div class="bp-grid">
          <div class="bp-item">
            <span class="bp-label">身高</span>
            <span class="bp-value" data-testid="bp-height">{{ num(profile.height, ' cm') }}</span>
          </div>
          <div class="bp-item">
            <span class="bp-label">当前体重</span>
            <span class="bp-value" data-testid="bp-weight">{{ num(profile.weight, ' kg') }}</span>
          </div>
          <div class="bp-item">
            <span class="bp-label">目标体重</span>
            <span class="bp-value">{{ num(profile.targetWeight, ' kg') }}</span>
          </div>
          <div class="bp-item">
            <span class="bp-label">BMI</span>
            <!-- ★ 直接显示接口返回值，前端一个公式都不重算（后端算的才算数） -->
            <span class="bp-value highlight" data-testid="bp-bmi">{{ num(profile.bmi) }}</span>
          </div>
          <div class="bp-item">
            <span class="bp-label">体脂率</span>
            <span class="bp-value">{{ num(profile.bodyFatRate, ' %') }}</span>
          </div>
          <div class="bp-item">
            <span class="bp-label">训练目标</span>
            <span class="bp-value" data-testid="bp-goal">{{ profile.goalTypeName || '—' }}</span>
          </div>
          <div class="bp-item">
            <span class="bp-label">训练水平</span>
            <span class="bp-value">{{ profile.trainingLevelName || '—' }}</span>
          </div>
          <div class="bp-item">
            <span class="bp-label">每周频次</span>
            <span class="bp-value" data-testid="bp-freq">{{ num(profile.weeklyFrequency, ' 次') }}</span>
          </div>
          <div class="bp-item">
            <span class="bp-label">腰围</span>
            <span class="bp-value">{{ num(profile.waist, ' cm') }}</span>
          </div>
          <div class="bp-item">
            <span class="bp-label">胸围</span>
            <span class="bp-value">{{ num(profile.chest, ' cm') }}</span>
          </div>
          <div class="bp-item">
            <span class="bp-label">臂围</span>
            <span class="bp-value">{{ num(profile.arm, ' cm') }}</span>
          </div>
          <div class="bp-item">
            <span class="bp-label">臀围</span>
            <span class="bp-value">{{ num(profile.hip, ' cm') }}</span>
          </div>
        </div>
        <div class="bp-extra">
          <span class="bp-label">伤病史</span>
          <span>{{ display(profile.injuryHistory) }}</span>
        </div>
        <div class="bp-foot">
          更新时间：{{ formatDateTime(profile.updateTime) }}
        </div>
      </div>
    </el-card>

    <el-card shadow="never" class="trade-card">
      <template #header>
        <div class="fm-card-title">快捷入口</div>
      </template>
      <div class="quick-links">
        <div
          v-for="link in quickLinks"
          :key="link.path"
          class="quick-link"
          :data-testid="link.testid"
          @click="router.push(link.path)"
        >
          <el-icon class="quick-icon"><component :is="link.icon" /></el-icon>
          <span class="quick-label">{{ link.label }}</span>
          <span class="quick-desc">{{ link.desc }}</span>
        </div>
      </div>
    </el-card>

    <el-alert class="source-tip" type="info" :closable="false">
      <template #title>
        本页所有字段直接来自 <code>GET /us/users/me</code> 与
        <code>GET /us/users/me/profile</code>，前端无任何硬编码 ——
        因此它也是验收脚本的交叉验证对象（页面显示值必须等于接口返回值）。
      </template>
    </el-alert>

    <!-- ==================== 编辑资料弹窗 ==================== -->
    <el-dialog v-model="editVisible" title="编辑资料" width="520px" append-to-body>
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="88px">
        <el-form-item label="头像">
          <div class="avatar-edit">
            <el-avatar :size="56" :src="editForm.icon || undefined">
              {{ avatarText }}
            </el-avatar>
            <el-upload
              :auto-upload="false"
              :show-file-list="false"
              accept="image/*"
              :on-change="handleAvatarChange"
            >
              <el-button data-testid="me-avatar-upload" size="small" :loading="uploading">
                {{ uploading ? `上传中 ${uploadPercent}%` : '更换头像' }}
              </el-button>
            </el-upload>
            <span class="field-hint">≤ 2MB，存的是不带签名的原始地址</span>
          </div>
        </el-form-item>

        <el-form-item label="昵称" prop="name">
          <el-input v-model="editForm.name" data-testid="me-edit-name" placeholder="姓名 / 昵称" clearable />
        </el-form-item>

        <el-form-item label="性别">
          <el-radio-group v-model="genderModel" data-testid="me-edit-gender">
            <el-radio :value="0">男</el-radio>
            <el-radio :value="1">女</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="生日">
          <el-date-picker
            v-model="editForm.birthday"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择日期"
            style="width: 100%"
          />
        </el-form-item>

        <el-form-item label="邮箱" prop="email">
          <el-input v-model="editForm.email" placeholder="选填" clearable />
        </el-form-item>

        <el-form-item label="QQ">
          <el-input v-model="editForm.qq" placeholder="选填" clearable />
        </el-form-item>

        <el-form-item label="所在地区">
          <div class="region-row">
            <el-input v-model="editForm.province" placeholder="省" />
            <el-input v-model="editForm.city" placeholder="市" />
            <el-input v-model="editForm.district" placeholder="区" />
          </div>
        </el-form-item>

        <el-form-item label="健身宣言">
          <el-input
            v-model="editForm.intro"
            type="textarea"
            :rows="2"
            maxlength="60"
            show-word-limit
            placeholder="一句话介绍你的训练目标"
          />
        </el-form-item>

        <el-divider content-position="left">
          <span class="divider-text">修改密码（不修改请留空）</span>
        </el-divider>

        <el-form-item label="原密码">
          <el-input
            v-model="editForm.oldPassword"
            data-testid="me-edit-old-pwd"
            type="password"
            placeholder="修改密码时必填"
            show-password
          />
        </el-form-item>

        <el-form-item label="新密码" prop="newPassword">
          <el-input
            v-model="editForm.newPassword"
            data-testid="me-edit-new-pwd"
            type="password"
            placeholder="至少 6 位"
            show-password
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button
          data-testid="me-edit-save"
          type="primary"
          :loading="saving"
          @click="submitEdit"
        >
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- ==================== 身体档案弹窗 ==================== -->
    <el-dialog v-model="profileVisible" title="身体档案" width="560px" append-to-body>
      <el-form :model="profileForm" label-width="96px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="身高">
              <el-input-number
                v-model="profileForm.height"
                data-testid="bp-form-height"
                :min="80"
                :max="250"
                :precision="1"
                :step="0.5"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="当前体重">
              <el-input-number
                v-model="profileForm.weight"
                data-testid="bp-form-weight"
                :min="20"
                :max="300"
                :precision="1"
                :step="0.5"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="目标体重">
              <el-input-number
                v-model="profileForm.targetWeight"
                :min="20"
                :max="300"
                :precision="1"
                :step="0.5"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="体脂率">
              <el-input-number
                v-model="profileForm.bodyFatRate"
                :min="1"
                :max="60"
                :precision="1"
                :step="0.5"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="腰围">
              <el-input-number
                v-model="profileForm.waist"
                :min="30"
                :max="200"
                :precision="1"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="胸围">
              <el-input-number
                v-model="profileForm.chest"
                :min="30"
                :max="200"
                :precision="1"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="臂围">
              <el-input-number
                v-model="profileForm.arm"
                :min="10"
                :max="100"
                :precision="1"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="臀围">
              <el-input-number
                v-model="profileForm.hip"
                :min="30"
                :max="200"
                :precision="1"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="训练目标">
          <el-select v-model="profileForm.goalType" placeholder="请选择" clearable style="width: 100%">
            <el-option v-for="o in GOAL_TYPE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>

        <el-form-item label="训练水平">
          <el-select v-model="profileForm.trainingLevel" placeholder="请选择" clearable style="width: 100%">
            <el-option
              v-for="o in TRAINING_LEVEL_OPTIONS"
              :key="o.value"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="每周频次">
          <el-select v-model="profileForm.weeklyFrequency" placeholder="请选择" clearable style="width: 100%">
            <el-option
              v-for="f in WEEKLY_FREQUENCY_OPTIONS"
              :key="f"
              :label="`${f} 次 / 周`"
              :value="f"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="伤病史">
          <el-input
            v-model="profileForm.injuryHistory"
            type="textarea"
            :rows="2"
            maxlength="100"
            show-word-limit
            placeholder="没有就留空"
          />
        </el-form-item>

        <el-alert type="info" :closable="false" show-icon>
          <template #title>
            BMI 由后端按身高体重自动计算（本表单不填 BMI）—— 所以这里看到的数值与页面上
            显示的一定一致，不存在"前端自己算一套"的偏差。
          </template>
        </el-alert>
      </el-form>

      <template #footer>
        <el-button @click="profileVisible = false">取消</el-button>
        <el-button
          data-testid="bp-form-save"
          type="primary"
          :loading="profileSaving"
          @click="submitProfile"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.trade-card {
  margin-top: 16px;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.head-actions {
  display: flex;
  gap: 8px;
}

.profile-wrap {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.avatar-block {
  display: flex;
  align-items: center;
  gap: 14px;
}

.avatar-name {
  font-size: 17px;
  font-weight: 600;
}

.avatar-role {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}

.avatar-edit {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.field-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.region-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.divider-text {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* ---- 身体档案展示 ---- */
.body-profile {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.bp-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px 20px;
}

.bp-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.bp-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.bp-value {
  font-size: 15px;
  font-weight: 600;
}

.bp-value.highlight {
  color: var(--el-color-primary);
}

.bp-extra {
  display: flex;
  gap: 8px;
  font-size: 13px;
}

.bp-foot {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.quick-links {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}

.quick-link {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.quick-link:hover {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.quick-icon {
  font-size: 20px;
  color: var(--el-color-primary);
}

.quick-label {
  font-size: 15px;
  font-weight: 600;
}

.quick-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-left: auto;
}

.source-tip {
  margin-top: 16px;
}

code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--el-fill-color);
  font-family: ui-monospace, Consolas, monospace;
}
</style>
