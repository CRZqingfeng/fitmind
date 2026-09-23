<script setup lang="ts">
import { onUnmounted, reactive, ref, watch } from 'vue'

import { Iphone, Lock, Message } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { sendCode } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { LOGIN_TYPE_CODE, LOGIN_TYPE_PASSWORD } from '@/types/user'
import { ApiError } from '@/utils/request'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/** 登录方式：验证码 / 密码 */
const mode = ref<'code' | 'password'>('code')

const formRef = ref<FormInstance>()
const form = reactive({
  /** ⚠️ 用户端只允许【学员】账号登录（后端 UserServiceImpl.queryUserDetail 会校验），
   *  管理员/教练请用管理端 —— 这里预填学员测试账号，方便直接体验 */
  cellPhone: '13800010001',
  /** 验证码或密码：两种模式共用一个字段，避免多写一个 state */
  credential: '',
  rememberMe: true,
})

const loading = ref(false)
const sending = ref(false)
const countdown = ref(0)
/** 登录失败信息就地展示，不用全局 toast —— 用户输错时视线就在表单上 */
const errorMsg = ref('')

let countdownTimer: ReturnType<typeof setInterval> | undefined

const CODE_COUNTDOWN_SECONDS = 60

const rules: FormRules = {
  cellPhone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' },
  ],
  credential: [{ required: true, message: '请输入验证码或密码', trigger: 'blur' }],
}

// 切换登录方式时清掉上一方式的输入值（验证码和密码不是同一个东西，留着只会让人误提交）
watch(mode, () => {
  form.credential = ''
  errorMsg.value = ''
})

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

// ★ 组件卸载必须清定时器，否则离开登录页后它还在跑（并且会试图改已销毁组件的状态）
onUnmounted(stopCountdown)

async function handleSendCode() {
  errorMsg.value = ''
  // 只校验手机号这一项：此时 credential 还没填，整体 validate 必然失败
  const phoneValid = await formRef.value
    ?.validateField('cellPhone')
    .then(() => true)
    .catch(() => false)
  if (!phoneValid) return

  sending.value = true
  try {
    const code = await sendCode(form.cellPhone)
    startCountdown()
    if (code) {
      // ★ Mock 模式：后端把验证码直接放在响应的 data 里返回，前端自动回填
      // 这是后端刻意做的开发便利（真实短信模式返回 null）
      form.credential = code
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

async function handleLogin() {
  errorMsg.value = ''
  const valid = await formRef.value
    ?.validate()
    .then(() => true)
    .catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const isCodeMode = mode.value === 'code'
    await auth.login({
      type: isCodeMode ? LOGIN_TYPE_CODE : LOGIN_TYPE_PASSWORD,
      // 两个字段都填手机号：后端验证码登录只读 cellPhone，
      // 密码登录则 username / cellPhone 任一即可 —— 都填上就不用关心它取哪个
      cellPhone: form.cellPhone,
      username: form.cellPhone,
      password: form.credential,
      rememberMe: form.rememberMe,
    })
    ElMessage.success(`欢迎回来，${auth.displayName || form.cellPhone}`)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch (e) {
    errorMsg.value = e instanceof ApiError ? e.message : '登录失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <h1>FitMind</h1>
        <p>AI 健身教练 · 让每一次训练有据可依</p>
      </div>

      <el-tabs v-model="mode" class="login-tabs">
        <el-tab-pane label="验证码登录" name="code" />
        <el-tab-pane label="密码登录" name="password" />
      </el-tabs>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        size="large"
        @submit.prevent="handleLogin"
      >
        <el-form-item prop="cellPhone">
          <el-input
            v-model="form.cellPhone"
            data-testid="login-phone"
            placeholder="手机号"
            :prefix-icon="Iphone"
            clearable
          />
        </el-form-item>

        <el-form-item prop="credential">
          <el-input
            v-if="mode === 'password'"
            v-model="form.credential"
            data-testid="login-credential"
            type="password"
            placeholder="密码"
            :prefix-icon="Lock"
            show-password
          />
          <div v-else class="code-row">
            <el-input
              v-model="form.credential"
              data-testid="login-credential"
              placeholder="验证码"
              :prefix-icon="Message"
            />
            <el-button
              data-testid="login-send-code"
              :disabled="countdown > 0"
              :loading="sending"
              @click="handleSendCode"
            >
              {{ countdown > 0 ? `${countdown}s` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>

        <el-form-item class="remember-item">
          <el-checkbox v-model="form.rememberMe">7 天免登录</el-checkbox>
        </el-form-item>

        <el-alert
          v-if="errorMsg"
          class="login-error"
          type="error"
          :title="errorMsg"
          :closable="false"
          show-icon
        />

        <el-button
          class="login-submit"
          data-testid="login-submit"
          type="primary"
          size="large"
          :loading="loading"
          @click="handleLogin"
        >
          登 录
        </el-button>
      </el-form>

      <el-alert class="login-hint" type="info" title="本地开发提示" :closable="false" show-icon>
        <div class="hint-body">
          <p>Mock 模式下验证码由后端直接返回并自动填充，无需短信。</p>
          <p>用户端仅支持<strong>学员</strong>账号：<code>13800010001</code></p>
        </div>
      </el-alert>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background:
    radial-gradient(circle at 15% 20%, rgba(255, 107, 53, 0.16), transparent 45%),
    radial-gradient(circle at 85% 75%, rgba(255, 107, 53, 0.1), transparent 45%), #f5f7fa;
}

.login-card {
  width: 100%;
  max-width: 400px;
  padding: 36px 32px 28px;
  background: #fff;
  border-radius: var(--fm-radius);
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.08);
}

.login-brand {
  text-align: center;
  margin-bottom: 20px;
}

.login-brand h1 {
  margin: 0;
  font-size: 30px;
  font-weight: 700;
  color: var(--el-color-primary);
  letter-spacing: 1px;
}

.login-brand p {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.login-tabs {
  margin-bottom: 4px;
}

.code-row {
  display: flex;
  gap: 10px;
  width: 100%;
}

.code-row .el-button {
  flex-shrink: 0;
  width: 108px;
}

.remember-item {
  margin-bottom: 12px;
}

.login-error {
  margin-bottom: 14px;
}

.login-submit {
  width: 100%;
  font-weight: 600;
  letter-spacing: 2px;
}

.login-hint {
  margin-top: 20px;
}

.hint-body {
  font-size: 12px;
  line-height: 1.7;
}

.hint-body p {
  margin: 0;
}

.hint-body code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--el-fill-color);
  font-family: ui-monospace, Consolas, monospace;
}
</style>
