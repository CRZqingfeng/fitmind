<script setup lang="ts">
import { Delete, Edit, Plus, Refresh, Sort } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'

import {
  addCategory,
  deleteCategory,
  getAllCategories,
  setCategoryStatus,
  updateCategory,
} from '@/api/category'
import type { CategoryVO } from '@/types/admin'
import { formatDateTime } from '@/utils/format'

/**
 * 课程分类管理（M7-12 第 2 期新增）
 *
 * ★ 三级树：训练目标(1) → 训练部位(2) → 器械场景(3)。
 *   用**树表**一屏看全，不做逐级钻取 —— 分类量很小（演示数据 69 条），
 *   钻取只会让人看不到全貌，而"这条挂在哪个父下面"恰恰是最需要一眼看清的事。
 *
 * ★★ 三处"照后端真实行为来"的设计（都读过源码，不是猜的）：
 *
 *  ① **不提供"移动分类"**：`CategoryUpdateDTO` 只有 `id / name / priority`，
 *     **没有 `parentId`** → 后端不支持换父。与其放一个会被静默忽略的控件，
 *     不如干脆不做（"UI 上有但后端不理"是最难排查的一类假象）。
 *
 *  ② **删除不做前端预判**：后端会因"有子分类"或"已被课程使用"而拒绝，
 *     而前端**判不准** —— `courseNum` 只统计**直接挂在该分类下**的课程，
 *     拿它当"能不能删"的依据会漏掉子分类里的课程。
 *     所以按钮照留，把**后端的报错原文**显示给用户：那才是权威理由。
 *
 *  ③ **已停用的分类照常显示**（打标签区分），不从树里藏掉 ——
 *     藏了会让人以为分类"丢了"，而它其实还在、只是不参与学员端筛选。
 */
const loading = ref(false)
const tree = ref<CategoryVO[]>([])
/** 正在操作的行 id（按行禁用按钮，避免连点） */
const acting = ref<string | null>(null)

const LEVEL_LABEL: Record<number, string> = { 1: '训练目标', 2: '训练部位', 3: '器械场景' }
const LEVEL_TAG: Record<number, 'primary' | 'success' | 'warning'> = {
  1: 'primary',
  2: 'success',
  3: 'warning',
}

async function load() {
  loading.value = true
  try {
    tree.value = (await getAllCategories()) ?? []
  } catch {
    /* 错误已由请求层提示 */
  } finally {
    loading.value = false
  }
}

// ==================== 新增 / 编辑 ====================

const dialog = reactive({
  visible: false,
  mode: 'add' as 'add' | 'edit',
  /** 弹窗标题里带上父分类名，让用户确认"加在哪儿" */
  parentLabel: '',
  form: {
    id: '',
    name: '',
    priority: 0,
    /** 一级分类固定 '0'（后端约定）；字符串形式遵守 Long→String 契约 */
    parentId: '0',
    level: 1,
  },
})
const submitting = ref(false)

/** 新增：`parent` 为空表示加一级分类 */
function openAdd(parent?: CategoryVO) {
  dialog.mode = 'add'
  dialog.parentLabel = parent ? `${LEVEL_LABEL[parent.level ?? 1]}「${parent.name}」` : '一级分类'
  dialog.form = {
    id: '',
    name: '',
    priority: 0,
    parentId: parent ? parent.id : '0',
    level: parent ? (parent.level ?? 1) + 1 : 1,
  }
  dialog.visible = true
}

function openEdit(row: CategoryVO) {
  dialog.mode = 'edit'
  dialog.parentLabel = LEVEL_LABEL[row.level ?? 1]
  dialog.form = {
    id: row.id,
    name: row.name,
    priority: row.priority ?? 0,
    parentId: row.parentId ?? '0',
    level: row.level ?? 1,
  }
  dialog.visible = true
}

async function submit() {
  const name = dialog.form.name.trim()
  if (!name) {
    ElMessage.warning('请填写分类名称')
    return
  }
  submitting.value = true
  try {
    if (dialog.mode === 'add') {
      await addCategory({
        name,
        parentId: dialog.form.parentId,
        priority: dialog.form.priority,
      })
      ElMessage.success('分类已新增')
    } else {
      await updateCategory({
        id: dialog.form.id,
        name,
        priority: dialog.form.priority,
      })
      ElMessage.success('分类已更新')
    }
    dialog.visible = false
    await load()
  } catch {
    /* 错误已由请求层提示（含后端给的具体原因） */
  } finally {
    submitting.value = false
  }
}

// ==================== 启停 / 删除 ====================

async function toggleStatus(row: CategoryVO) {
  const next = row.status === 1 ? 2 : 1
  if (next === 2) {
    /**
     * ★ 停用要确认、启用不用 —— 两个方向的影响不对称：
     *   停用会让它**从学员端的分类筛选里消失**（已选它的课程也少一个入口），
     *   启用只是恢复。给低风险的方向加确认框，只会训练用户"无脑点确定"。
     */
    try {
      await ElMessageBox.confirm(
        `停用后「${row.name}」不再参与学员端筛选（已挂在该分类下的课程不受影响）。确认停用？`,
        '停用分类',
        { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  acting.value = row.id
  try {
    await setCategoryStatus({ id: row.id, status: next })
    ElMessage.success(next === 1 ? '已启用' : '已停用')
    await load()
  } catch {
    /* 已提示 */
  } finally {
    acting.value = null
  }
}

async function handleDelete(row: CategoryVO) {
  try {
    await ElMessageBox.confirm(
      `确认删除「${row.name}」？\n\n注意：如果它下面还有子分类、或者已经被课程使用，后端会拒绝并给出原因。`,
      '删除分类',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  acting.value = row.id
  try {
    await deleteCategory(row.id)
    ElMessage.success('分类已删除')
    await load()
  } catch {
    /* ★ 这里不做兜底文案：后端会明确说"有子分类/已被课程使用"，
         吞掉换一句"删除失败"等于把最有用的信息丢掉 */
  } finally {
    acting.value = null
  }
}

onMounted(load)
</script>

<template>
  <div class="fm-page category-admin">
    <el-card shadow="never" class="filter-card">
      <div class="filter-row">
        <el-button type="primary" :icon="Plus" data-testid="cate-add-root" @click="openAdd()">
          新增一级分类
        </el-button>
        <el-button :icon="Refresh" data-testid="cate-refresh" @click="load">刷新</el-button>
        <span class="total-hint" data-testid="cate-total">
          共 {{ tree.length }} 个一级分类
        </span>
      </div>

      <div class="hint-line">
        <p>
          三级结构：<strong>训练目标 → 训练部位 → 器械场景</strong>。学员端用前两级做筛选，
          第三级是"器械场景"。
        </p>
        <p>
          ★ 排序值 <strong>越大越靠前</strong>（后端原话）。分类
          <strong>不支持换父级</strong> —— 接口只收名称与排序值，所以本页也没有"移动"按钮。
        </p>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table
        v-loading="loading"
        :data="tree"
        row-key="id"
        :tree-props="{ children: 'children' }"
        default-expand-all
        data-testid="cate-table"
      >
        <el-table-column label="分类名称" min-width="260">
          <template #default="{ row }">
            <span class="cate-name">{{ row.name }}</span>
            <el-tag v-if="row.status === 2" size="small" type="info" class="cate-off">已停用</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="层级" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="LEVEL_TAG[row.level ?? 1]">
              {{ LEVEL_LABEL[row.level ?? 1] ?? `L${row.level}` }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="排序值" width="90" prop="priority" />
        <el-table-column label="课程数" width="90" prop="courseNum" />
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <!-- ★ 三级到底：level=3 不再允许加子分类（后端树只有三层） -->
            <el-button
              v-if="(row.level ?? 1) < 3"
              link
              type="primary"
              :icon="Plus"
              :disabled="acting === row.id"
              :data-testid="`cate-add-child-${row.id}`"
              @click="openAdd(row as CategoryVO)"
            >
              子分类
            </el-button>
            <el-button
              link
              type="primary"
              :icon="Edit"
              :disabled="acting === row.id"
              :data-testid="`cate-edit-${row.id}`"
              @click="openEdit(row as CategoryVO)"
            >
              编辑
            </el-button>
            <el-button
              link
              :type="row.status === 1 ? 'warning' : 'success'"
              :icon="Sort"
              :loading="acting === row.id"
              :data-testid="`cate-toggle-${row.id}`"
              @click="toggleStatus(row as CategoryVO)"
            >
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button
              link
              type="danger"
              :icon="Delete"
              :disabled="acting === row.id"
              :data-testid="`cate-delete-${row.id}`"
              @click="handleDelete(row as CategoryVO)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialog.visible"
      :title="dialog.mode === 'add' ? `新增分类 · ${dialog.parentLabel}` : `编辑分类 · ${dialog.parentLabel}`"
      width="460px"
      data-testid="cate-dialog"
    >
      <el-form label-width="80px" @submit.prevent="submit">
        <el-form-item label="分类名称" required>
          <el-input
            v-model="dialog.form.name"
            placeholder="如：增肌 / 胸部 / 哑铃"
            maxlength="32"
            show-word-limit
            data-testid="cate-form-name"
          />
        </el-form-item>
        <el-form-item label="排序值">
          <el-input-number
            v-model="dialog.form.priority"
            :min="0"
            :max="9999"
            data-testid="cate-form-priority"
          />
          <span class="form-tip">越大越靠前</span>
        </el-form-item>
        <!--
          ★ 这里**不显示"父分类"选择器**：新增子分类的入口本来就在某一行的"子分类"按钮上，
             父级在点进来的那一刻就已经确定了。再给一个下拉反而让人怀疑"是不是选错了"。
        -->
      </el-form>

      <template #footer>
        <el-button data-testid="cate-form-cancel" @click="dialog.visible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          data-testid="cate-form-submit"
          @click="submit"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter-card {
  margin-bottom: 16px;
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.total-hint {
  margin-left: auto;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.hint-line {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--el-border-color-lighter);
  font-size: 12px;
  line-height: 1.9;
  color: var(--el-text-color-secondary);
}

.hint-line p {
  margin: 0;
}

.cate-name {
  font-weight: 500;
}

.cate-off {
  margin-left: 8px;
}

.form-tip {
  margin-left: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
