<script setup lang="ts">
import { onUnmounted, reactive, ref } from 'vue'

import { Iphone, Lock, Message, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { useRouter } from 'vue-router'

import { checkCellphone, registerStudent, sendCode } from '@/api/auth'
import type { StudentForm } from '@/types/user'
import { ApiError } from '@/utils/request'

/**
 * 学员注册（M7-12）
 *
 * ★ 这是 **M7 方案 §7.1 第 1 页「登录 / 注册」里承诺过、但一直没交付**的那一半 ——
 *   原来的登录页只有"验证码登录 / 密码登录"两种**登录**方式，没有注册入口。
 *
 * 两个接口配合：
 *   ① `POST /us/codes`              发验证码（Mock 模式后端直接返回码值 → 自动回填）
 *   ② `POST /us/students/register`  提交注册（**免登录**，网关 excludePath 里专门放行）
 *
 * ⚠️ 注册接口**不返回 token** —— 后端 `registerStudent` 的返回值是 void。
 *   所以注册成功后是"跳回登录页并带上手机号"，而不是直接进首页。
 *
 * ============================================================
 * ★ 关于"手机号可用性预校验"：踩了坑，又救回来了
 * ============================================================
 * 后端有正合适的接口：`GET /us/users/checkCellphone?cellphone=`（返回 true = **可注册**）。
 *
 *   第一版把它接在手机号的 `@blur` 上 → **注册页被弄坏了**：
 *   那时该接口**要求登录**（网关 `requireLoginPath` 的 `/us/**` 覆盖了它，
 *   `excludePath` 只放行了 codes / register），而注册页恰恰是**免登录页**。
 *   于是用户一失焦就拿到 401 → 全局响应拦截器判"登录态过期" →
 *   续期也失败 → `forceLogin()` → **整页跳去 `/login?redirect=/register`**。
 *   → 当时**撤掉了预校验**（比"为了调一个接口把用户踢走"重要），
 *     并把"这两个登录前接口被登录墙挡住"登记为 **#A16**。
 *
 *   #A16 修好后（网关 `excludePath` 补了 `GET:/us/users/checkCellphone`），
 *   预校验**恢复**了 —— 见下面的 `handleCheckPhone`。
 *
 * ★ 但它只是**体验优化，不是防线**：用户把 4 个字段都填完、点了提交才被告知
 *   "这个号已注册"体验很差，所以提前查一次；真正确认能不能注册的仍然是
 *   `POST /us/students/register`（两次请求之间别人可能刚好注册了同一个号）。
 */
const router = useRouter()

const formRef = ref<FormInstance>()
const form = reactive({
  cellPhone: '',
  code: '',
  password: '',
  /** ★ 仅前端校验用，**不提交给后端**（后端 StudentFormDTO 没有这个字段） */
  confirmPassword: '',
  name: '',
})

const loading = ref(false)
const sending = ref(false)
const countdown = ref(0)
/** 注册失败就地展示（视线在表单上，比左下角 toast 更友好） */
const errorMsg = ref('')
/** 手机号预校验结果：null = 还没查 / true = 可以注册 / false = 已注册 */
const phoneUsable = ref<boolean | null>(null)

let countdownTimer: ReturnType<typeof setInterval> | undefined
const CODE_COUNTDOWN_SECONDS = 60

const PHONE_RE = /^1[3-9]\d{9}$/

function validateConfirm(_rule: unknown, value: string, callback: (e?: Error) => void) {
  if (!value) return callback(new Error('请再次输入密码'))
  if (value !== form.password) return callback(new Error('两次输入的密码不一致'))
  return callback()
}

const rules: FormRules = {
  cellPhone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: PHONE_RE, message: '手机号格式不正确', trigger: 'blur' },
  ],
  code: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
  password: [
    { required: true, message: '请设置密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' },
  ],
  confirmPassword: [{ validator: validateConfirm, trigger: 'blur' }],
}

function startCountdown() {
  countdown.value = CODE_COUNTDOWN_SECONDS
  countdownTimer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) stopCountdown()
  }, 1000)
}

function stopCountdown() {
  if (countdownTimer) {
    clearInterval(countdownTimer)
    countdownTimer = undefined
  }
  countdown.value = 0
}

// ★ 组件卸载必须清定时器（同 LoginView：离开页面后它还在跑，并试图改已销毁组件的状态）
onUnmounted(stopCountdown)

/**
 * 手机号失焦时查一次"能不能注册"（#A16 修好后恢复的能力）。
 *
 * ★ 提前查的价值：用户把 4 个字段都填完、点了提交才被告知"这个号已注册"，体验很差。
 * ★ 但它**不是防线**（见文件头）：查不通也不打断注册流程，
 *   让 `POST /us/students/register` 的权威校验说了算。
 */
async function handleCheckPhone() {
  phoneUsable.value = null
  if (!PHONE_RE.test(form.cellPhone)) return
  try {
    const usable = await checkCellphone(form.cellPhone)
    phoneUsable.value = usable
    if (!usable) errorMsg.value = '该手机号已注册，请直接登录或找回密码'
  } catch {
    // 查不通就静默 —— 不因为一个"体验优化"接口挡住注册主流程
    phoneUsable.value = null
  }
}

/**
 * 用户改动手机号后，上一次的预校验结果就作废了。
 * ★ 不清的话会出现很怪的拦截：改成新号码后点"获取验证码"，却用**旧号码**的结论拦住。
 */
function handlePhoneInput() {
  phoneUsable.value = null
  errorMsg.value = ''
}

/**
 * 发验证码。
 *
 * ★ 预校验结果在这里**再拦一道**：已注册的号不必浪费一条短信。
 *   但这仍不是防线 —— 真正确认"能不能注册"的是提交时的后端校验。
 */
async function handleSendCode() {
  errorMsg.value = ''
  // 只校验手机号那一项：此时其他字段还没填，整体 validate 必然失败
  const ok = await formRef.value
    ?.validateField('cellPhone')
    .then(() => true)
    .catch(() => false)
  if (!ok) return
  if (phoneUsable.value === false) {
    errorMsg.value = '该手机号已注册，请直接登录或找回密码'
    return
  }

  sending.value = true
  try {
    const code = await sendCode(form.cellPhone)
    startCountdown()
    if (code) {
      // ★ Mock 模式：后端把验证码直接放在 data 里返回（真实短信模式返回 null）
      form.code = code
      ElMessage.success(`Mock 模式：验证码已自动填充（${code}）`)
    } else {
      ElMessage.success('验证码已发送，请查收短信')
    }
  } catch (e) {
    errorMsg.value = e instanceof ApiError ? e.message : '验证码发送失败'
  } finally {
    sending.value = false
  }
}

async function handleRegister() {
  errorMsg.value = ''
  const valid = await formRef.value
    ?.validate()
    .then(() => true)
    .catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const payload: StudentForm = {
      cellPhone: form.cellPhone,
      code: form.code,
      password: form.password,
      // 昵称选填：留空就传 undefined，**不要传空串**（后端按"非空"判断是否写入）
      ...(form.name.trim() ? { name: form.name.trim() } : {}),
    }
    await registerStudent(payload)
    ElMessage.success('注册成功，请登录')
    // 带上手机号回登录页，用户不用再敲一遍
    await router.replace({ name: 'login', query: { phone: form.cellPhone } })
  } catch (e) {
    errorMsg.value = e instanceof ApiError ? e.message : '注册失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="register-page">
    <div class="register-card">
      <div class="register-brand">
        <h1>FitMind</h1>
        <p>注册新账号 · 开始你的训练计划</p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        size="large"
        @submit.prevent="handleRegister"
      >
        <el-form-item prop="cellPhone">
          <el-input
            v-model="form.cellPhone"
            data-testid="register-phone"
            placeholder="手机号"
            :prefix-icon="Iphone"
            clearable
            @blur="handleCheckPhone"
            @input="handlePhoneInput"
          />
        </el-form-item>

        <el-form-item prop="code">
          <div class="code-row">
            <el-input
              v-model="form.code"
              data-testid="register-code"
              placeholder="验证码"
              :prefix-icon="Message"
            />
            <el-button
              data-testid="register-send-code"
              :disabled="countdown > 0"
              :loading="sending"
              @click="handleSendCode"
            >
              {{ countdown > 0 ? `${countdown}s` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>

        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            data-testid="register-password"
            type="password"
            placeholder="设置密码（至少 6 位）"
            :prefix-icon="Lock"
            show-password
          />
        </el-form-item>

        <el-form-item prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            data-testid="register-confirm-password"
            type="password"
            placeholder="确认密码"
            :prefix-icon="Lock"
            show-password
          />
        </el-form-item>

        <el-form-item prop="name">
          <el-input
            v-model="form.name"
            data-testid="register-name"
            placeholder="昵称（选填）"
            :prefix-icon="User"
            clearable
          />
        </el-form-item>

        <el-alert
          v-if="errorMsg"
          class="register-error"
          type="error"
          :title="errorMsg"
          :closable="false"
          show-icon
        />
        <el-alert
          v-else-if="phoneUsable === true"
          class="register-ok"
          type="success"
          title="该手机号可以注册"
          :closable="false"
          show-icon
        />

        <el-button
          class="register-submit"
          data-testid="register-submit"
          type="primary"
          size="large"
          :loading="loading"
          @click="handleRegister"
        >
          注 册
        </el-button>
      </el-form>

      <div class="register-foot">
        <span>已有账号？</span>
        <el-link data-testid="register-go-login" type="primary" @click="router.replace('/login')">
          返回登录
        </el-link>
      </div>

      <el-alert class="register-hint" type="info" title="本地开发提示" :closable="false" show-icon>
        <div class="hint-body">
          <p>Mock 模式下验证码由后端直接返回并自动填充，无需短信。</p>
          <p>★ 注册会**真的往库里加一个学员账号**，验收脚本跑完会自行清理。</p>
        </div>
      </el-alert>
    </div>
  </div>
</template>

<style scoped>
.register-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background:
    radial-gradient(circle at 15% 20%, rgba(255, 107, 53, 0.16), transparent 45%),
    radial-gradient(circle at 85% 75%, rgba(255, 107, 53, 0.1), transparent 45%), #f5f7fa;
}

.register-card {
  width: 100%;
  max-width: 400px;
  padding: 32px 32px 24px;
  background: #fff;
  border-radius: var(--fm-radius);
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.08);
}

.register-brand {
  text-align: center;
  margin-bottom: 20px;
}

.register-brand h1 {
  margin: 0;
  font-size: 30px;
  font-weight: 700;
  color: var(--el-color-primary);
  letter-spacing: 1px;
}

.register-brand p {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.code-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.code-row .el-input {
  flex: 1;
}

.register-error,
.register-ok {
  margin-bottom: 12px;
}

.register-submit {
  width: 100%;
}

.register-foot {
  margin-top: 16px;
  text-align: center;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.register-hint {
  margin-top: 16px;
}

.hint-body p {
  margin: 2px 0;
  font-size: 12px;
  line-height: 1.6;
}
</style>
