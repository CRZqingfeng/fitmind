<script setup lang="ts">
import { Delete, Edit, Plus, Promotion, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'

import {
  createNoticeTemplate,
  deleteNoticeTemplate,
  getNoticeTemplate,
  getNoticeTemplatePage,
  getPublicNoticePage,
  publishNotice,
  updateNoticeTemplate,
} from '@/api/message'
import type { NoticeTemplate, NoticeTemplateForm, PublicNotice, PublicNoticeForm } from '@/types/admin'
import { NOTICE_TEMPLATE_STATUS, NOTICE_TEMPLATE_STATUS_OPTIONS, NOTICE_TYPE_OPTIONS } from '@/types/admin'
import { formatDateTime, pageTotal } from '@/utils/format'

/**
 * 公告与通知模板（M7-12 第 3 期新增）
 *
 * ============================================================
 * ★★ 两组能力别混为一谈
 * ============================================================
 *
 * | | 通知模板 | 公告 |
 * |---|---|---|
 * | 面向谁 | **单个用户**（程序按 `code` 找模板 → 填占位符 → 发） | **所有人** |
 * | 这里的职责 | CRUD（**配置**，不是"发送"） | 发布 + 查看 |
 *
 * → 所以模板 tab 没有"发送"按钮：它只负责把模板配置好，
 *   真正触发发送是业务代码（比如"购买成功"）通过内部接口做的。
 *   编一个假的"发送"按钮会让人以为这里能群发。
 *
 * ============================================================
 * ★★ 公告的"定时"是一个**时间字段**，没有调度器
 * ============================================================
 * `pushTime` 填**未来时间 = 定时生效** —— 而学员端
 * `GET /sms/messages/page` 的注释写着"会先把**新**公告展开进来"：
 * 也就是说"到时可见"是**读的时候按 `pushTime <= now` 过滤**出来的。
 * → 好处很实际：**填个未来时间，立刻就能验证它"还没到点、确实不可见"**，不用等。
 *
 * ============================================================
 * ★ 一条硬约束：模板的 `code` **不可修改**
 * ============================================================
 * `PUT` 的 `@Operation` 原话是"模板代号不可改"。它是**程序找模板的 key**，
 * 改了等于把线上所有依赖它的调用打断。所以编辑态把 `code` 置为**只读**，
 * 并且不在提交体里依赖它 —— 后端也会忽略这个字段。
 */
const activeTab = ref<'template' | 'notice'>('template')

// ==================== 通知模板 ====================

const tplLoading = ref(false)
const templates = ref<NoticeTemplate[]>([])
const tplTotal = ref(0)
const tplQuery = reactive({ pageNo: 1, pageSize: 10 })
const tplActing = ref<string | null>(null)

async function loadTemplates() {
  tplLoading.value = true
  try {
    const page = await getNoticeTemplatePage({ pageNo: tplQuery.pageNo, pageSize: tplQuery.pageSize })
    templates.value = page?.list ?? []
    tplTotal.value = pageTotal(page)
  } catch {
    /* 已提示 */
  } finally {
    tplLoading.value = false
  }
}

const tplDialog = reactive({ visible: false, mode: 'add' as 'add' | 'edit' })
const tplSubmitting = ref(false)
const tplDetailLoading = ref(false)
const tplForm = reactive<NoticeTemplateForm>({
  name: '',
  code: '',
  type: 0,
  status: NOTICE_TEMPLATE_STATUS.DRAFT,
  title: '',
  content: '',
  isSmsTemplate: 0,
})

function openTplAdd() {
  tplDialog.mode = 'add'
  Object.assign(tplForm, {
    id: undefined,
    name: '',
    code: '',
    type: 0,
    status: NOTICE_TEMPLATE_STATUS.DRAFT,
    title: '',
    content: '',
    isSmsTemplate: 0,
  })
  tplDialog.visible = true
}

async function openTplEdit(row: NoticeTemplate) {
  tplDialog.mode = 'edit'
  tplDialog.visible = true
  tplDetailLoading.value = true
  try {
    // ★ 编辑前拉一次详情（列表项虽然字段齐全，但"详情接口存在且可用"本身值得被走一遍）
    const d = await getNoticeTemplate(row.id)
    Object.assign(tplForm, {
      id: d.id,
      name: d.name,
      code: d.code,
      type: d.type ?? 0,
      status: d.status ?? NOTICE_TEMPLATE_STATUS.DRAFT,
      title: d.title,
      content: d.content,
      isSmsTemplate: d.isSmsTemplate ?? 0,
    })
  } catch {
    tplDialog.visible = false
  } finally {
    tplDetailLoading.value = false
  }
}

async function submitTpl() {
  if (!tplForm.name.trim()) return ElMessage.warning('请填写模板名称')
  if (!tplForm.code.trim()) return ElMessage.warning('请填写模板代号')
  if (!tplForm.title.trim()) return ElMessage.warning('请填写通知标题')
  if (!tplForm.content.trim()) return ElMessage.warning('请填写通知内容')
  tplSubmitting.value = true
  try {
    if (tplDialog.mode === 'add') {
      await createNoticeTemplate({ ...tplForm })
      ElMessage.success('模板已新增')
    } else {
      await updateNoticeTemplate({ ...tplForm })
      ElMessage.success('模板已更新')
    }
    tplDialog.visible = false
    await loadTemplates()
  } catch {
    /* 已提示 */
  } finally {
    tplSubmitting.value = false
  }
}

async function removeTpl(row: NoticeTemplate) {
  try {
    await ElMessageBox.confirm(
      `确认删除模板「${row.name}」（${row.code}）？\n\n` +
        '注意：如果线上有代码正在按这个 code 找模板，删除会让那类通知**发不出去**。',
      '删除模板',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  tplActing.value = row.id
  try {
    await deleteNoticeTemplate(row.id)
    ElMessage.success('模板已删除')
    await loadTemplates()
  } catch {
    /* 已提示 */
  } finally {
    tplActing.value = null
  }
}

// ==================== 公告 ====================

const noticeLoading = ref(false)
const notices = ref<PublicNotice[]>([])
const noticeTotal = ref(0)
const noticeQuery = reactive({ pageNo: 1, pageSize: 10 })

async function loadNotices() {
  noticeLoading.value = true
  try {
    const page = await getPublicNoticePage({ pageNo: noticeQuery.pageNo, pageSize: noticeQuery.pageSize })
    notices.value = page?.list ?? []
    noticeTotal.value = pageTotal(page)
  } catch {
    /* 已提示 */
  } finally {
    noticeLoading.value = false
  }
}

const noticeForm = reactive<PublicNoticeForm>({
  type: 0,
  title: '',
  content: '',
  pushTime: undefined,
  expireTime: undefined,
})
const publishing = ref(false)

/** 这条公告是"立即发"还是"定时发"（界面上一眼能看出来） */
const pushMode = computed(() => (noticeForm.pushTime ? '定时发布' : '立即发布'))

async function submitNotice() {
  if (!noticeForm.title.trim()) return ElMessage.warning('请填写公告标题')
  if (!noticeForm.content.trim()) return ElMessage.warning('请填写公告内容')
  publishing.value = true
  try {
    await publishNotice({ ...noticeForm })
    ElMessage.success(pushMode.value === '定时发布' ? '已提交，到点自动可见' : '公告已发布')
    noticeForm.title = ''
    noticeForm.content = ''
    noticeForm.pushTime = undefined
    noticeForm.expireTime = undefined
    noticeQuery.pageNo = 1
    await loadNotices()
  } catch {
    /* 已提示 */
  } finally {
    publishing.value = false
  }
}

const typeLabel = (t: number | null) => NOTICE_TYPE_OPTIONS.find((o) => o.value === t)?.label ?? '—'
const statusLabel = (s: number | null) =>
  NOTICE_TEMPLATE_STATUS_OPTIONS.find((o) => o.value === s)?.label ?? '—'
const statusTagType = (s: number | null) =>
  s === NOTICE_TEMPLATE_STATUS.USING ? 'success' : s === NOTICE_TEMPLATE_STATUS.DISABLED ? 'info' : 'warning'

onMounted(loadTemplates)
</script>

<template>
  <div class="fm-page notice-admin">
    <el-card shadow="never" class="head-card">
      <div class="hint-line">
        <p>
          <strong>通知模板</strong>=给<strong>单个用户</strong>发通知的配置（业务代码按
          <code>code</code> 找模板 → 填占位符 → 发），这一页只负责<strong>配置</strong>，不负责"发送"。
        </p>
        <p>
          <strong>公告</strong>=发给<strong>所有人</strong>。<code>pushTime</code> 填
          <strong>未来时间即定时生效</strong> —— 学员端是按"发布时间 ≤ 现在"过滤出来的，
          所以到点才可见，<strong>不需要任何等待</strong>。
        </p>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-tabs v-model="activeTab" data-testid="notice-tabs">
        <!-- ==================== 通知模板 ==================== -->
        <el-tab-pane label="通知模板" name="template">
          <div class="filter-row">
            <el-button type="primary" :icon="Plus" data-testid="notice-tpl-add" @click="openTplAdd">
              新建模板
            </el-button>
            <el-button :icon="Refresh" data-testid="notice-tpl-refresh" @click="loadTemplates">刷新</el-button>
            <span class="total-hint" data-testid="notice-tpl-total">共 {{ tplTotal }} 个模板</span>
          </div>

          <el-table v-loading="tplLoading" :data="templates" data-testid="notice-tpl-table">
            <el-table-column label="模板名称" min-width="180">
              <template #default="{ row }">
                <div class="title" :data-testid="`notice-tpl-name-${row.id}`">{{ row.name }}</div>
                <div class="sub">code：{{ row.code }}</div>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="90">
              <template #default="{ row }">{{ typeLabel(row.type) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="statusTagType(row.status)">{{ statusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="标题模板" min-width="200" prop="title" show-overflow-tooltip />
            <el-table-column label="短信" width="80">
              <template #default="{ row }">
                <el-tag v-if="row.isSmsTemplate === 1" size="small" type="warning">是</el-tag>
                <span v-else class="sub">否</span>
              </template>
            </el-table-column>
            <el-table-column label="更新时间" width="160">
              <template #default="{ row }">{{ formatDateTime(row.updateTime) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  :icon="Edit"
                  :data-testid="`notice-tpl-edit-${row.id}`"
                  @click="openTplEdit(row as NoticeTemplate)"
                >
                  编辑
                </el-button>
                <el-button
                  link
                  type="danger"
                  :icon="Delete"
                  :loading="tplActing === row.id"
                  :data-testid="`notice-tpl-delete-${row.id}`"
                  @click="removeTpl(row as NoticeTemplate)"
                >
                  删除
                </el-button>
              </template>
            </el-table-column>

            <template #empty>
              <div class="empty-tip">还没有通知模板</div>
            </template>
          </el-table>

          <el-pagination
            v-if="tplTotal > tplQuery.pageSize"
            v-model:current-page="tplQuery.pageNo"
            class="pager"
            layout="prev, pager, next"
            :page-size="tplQuery.pageSize"
            :total="tplTotal"
            data-testid="notice-tpl-pager"
            @current-change="loadTemplates"
          />
        </el-tab-pane>

        <!-- ==================== 公告 ==================== -->
        <el-tab-pane label="公告" name="notice">
          <el-form label-width="100px" class="publish-form" @submit.prevent="submitNotice">
            <el-form-item label="公告类型">
              <el-radio-group v-model="noticeForm.type" data-testid="notice-form-type">
                <el-radio-button :value="0" data-testid="notice-type-system">系统</el-radio-button>
                <el-radio-button :value="3" data-testid="notice-type-other">其它</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="标题" required>
              <el-input
                v-model="noticeForm.title"
                placeholder="如：国庆假期课程安排调整"
                maxlength="80"
                show-word-limit
                data-testid="notice-form-title"
              />
            </el-form-item>
            <el-form-item label="内容" required>
              <el-input
                v-model="noticeForm.content"
                type="textarea"
                :rows="3"
                placeholder="公告正文"
                data-testid="notice-form-content"
              />
            </el-form-item>
            <el-form-item label="发布时间">
              <div class="inline-row">
                <div class="date-wrap" data-testid="notice-form-pushtime-wrap">
                  <el-date-picker
                    v-model="noticeForm.pushTime"
                    type="datetime"
                    value-format="YYYY-MM-DDTHH:mm:ss"
                    placeholder="留空 = 立即发布"
                  />
                </div>
                <el-tag size="small" :type="noticeForm.pushTime ? 'warning' : 'success'" data-testid="notice-push-mode">
                  {{ pushMode }}
                </el-tag>
                <span class="form-tip">★ 填未来时间 = 定时，学员端到点才可见</span>
              </div>
            </el-form-item>
            <el-form-item label="失效时间">
              <div class="date-wrap" data-testid="notice-form-expire-wrap">
                <el-date-picker
                  v-model="noticeForm.expireTime"
                  type="datetime"
                  value-format="YYYY-MM-DDTHH:mm:ss"
                  placeholder="留空 = 按默认有效期"
                />
              </div>
            </el-form-item>
            <el-form-item>
              <el-button
                type="primary"
                :icon="Promotion"
                :loading="publishing"
                data-testid="notice-publish"
                @click="submitNotice"
              >
                发布公告
              </el-button>
            </el-form-item>
          </el-form>

          <el-divider content-position="left">已发布的公告</el-divider>

          <div class="filter-row">
            <el-button :icon="Search" data-testid="notice-list-refresh" @click="loadNotices">刷新列表</el-button>
            <span class="total-hint" data-testid="notice-total">共 {{ noticeTotal }} 条公告</span>
          </div>

          <el-table v-loading="noticeLoading" :data="notices" data-testid="notice-table">
            <el-table-column label="标题 / 内容" min-width="280">
              <template #default="{ row }">
                <div class="title" :data-testid="`notice-title-${row.id}`">{{ row.title }}</div>
                <div class="sub">{{ row.content }}</div>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="90">
              <template #default="{ row }">{{ typeLabel(row.type) }}</template>
            </el-table-column>
            <el-table-column label="发布时间" width="170">
              <template #default="{ row }">
                <span :data-testid="`notice-pushtime-${row.id}`">{{ formatDateTime(row.pushTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="失效时间" width="170">
              <template #default="{ row }">{{ formatDateTime(row.expireTime) }}</template>
            </el-table-column>
            <el-table-column label="创建时间" width="170">
              <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
            </el-table-column>

            <template #empty>
              <div class="empty-tip">还没有公告</div>
            </template>
          </el-table>

          <el-pagination
            v-if="noticeTotal > noticeQuery.pageSize"
            v-model:current-page="noticeQuery.pageNo"
            class="pager"
            layout="prev, pager, next"
            :page-size="noticeQuery.pageSize"
            :total="noticeTotal"
            data-testid="notice-pager"
            @current-change="loadNotices"
          />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 模板弹窗 -->
    <el-dialog
      v-model="tplDialog.visible"
      :title="tplDialog.mode === 'add' ? '新建通知模板' : '编辑通知模板'"
      width="620px"
      data-testid="notice-tpl-dialog"
    >
      <el-form v-loading="tplDetailLoading" label-width="100px" @submit.prevent="submitTpl">
        <el-form-item label="模板名称" required>
          <el-input v-model="tplForm.name" placeholder="如：购买成功通知" data-testid="notice-tpl-form-name" />
        </el-form-item>
        <el-form-item label="模板代号" required>
          <el-input
            v-model="tplForm.code"
            placeholder="如 purchase-success"
            :disabled="tplDialog.mode === 'edit'"
            data-testid="notice-tpl-form-code"
          />
          <div class="form-tip">
            {{ tplDialog.mode === 'edit'
              ? '★ 模板代号不可修改（它是程序找模板的 key，改了会打断线上调用）'
              : '程序按它找模板，用短横线小写，如 purchase-success' }}
          </div>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="通知类型">
              <el-select v-model="tplForm.type" style="width: 100%" data-testid="notice-tpl-form-type">
                <el-option
                  v-for="t in NOTICE_TYPE_OPTIONS"
                  :key="t.value"
                  :label="t.label"
                  :value="t.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="模板状态">
              <el-select v-model="tplForm.status" style="width: 100%" data-testid="notice-tpl-form-status">
                <el-option
                  v-for="s in NOTICE_TEMPLATE_STATUS_OPTIONS"
                  :key="s.value"
                  :label="s.label"
                  :value="s.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="通知标题" required>
          <el-input
            v-model="tplForm.title"
            placeholder="支持 {占位符}，如：你的课程《{courseName}》已开通"
            data-testid="notice-tpl-form-title"
          />
        </el-form-item>
        <el-form-item label="通知内容" required>
          <el-input
            v-model="tplForm.content"
            type="textarea"
            :rows="3"
            placeholder="支持 {占位符}，如：{userName} 你好，…"
            data-testid="notice-tpl-form-content"
          />
        </el-form-item>
        <el-form-item label="同时发短信">
          <el-switch
            v-model="tplForm.isSmsTemplate"
            :active-value="1"
            :inactive-value="0"
            data-testid="notice-tpl-form-sms"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button data-testid="notice-tpl-form-cancel" @click="tplDialog.visible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="tplSubmitting"
          data-testid="notice-tpl-form-submit"
          @click="submitTpl"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.head-card {
  margin-bottom: 16px;
}

.hint-line {
  font-size: 12px;
  line-height: 1.9;
  color: var(--el-text-color-secondary);
}

.hint-line p {
  margin: 0;
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}

.total-hint {
  margin-left: auto;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.title {
  font-weight: 500;
}

.sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}

.publish-form {
  max-width: 720px;
}

.inline-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.date-wrap {
  width: 240px;
}

.form-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.empty-tip {
  padding: 20px 0;
  color: var(--el-text-color-secondary);
}

.pager {
  margin-top: 16px;
  justify-content: center;
}
</style>
