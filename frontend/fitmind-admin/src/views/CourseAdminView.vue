<script setup lang="ts">
import { Collection, Delete, Download, EditPen, Plus, Refresh, Search, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import {
  checkBeforeUpShelf,
  deleteCourse,
  downShelf,
  getCategoryTree,
  getCoursePage,
  upShelf,
} from '@/api/course'
import CoverThumb from '@/components/CoverThumb.vue'
import type { CategoryVO, CoursePageVO } from '@/types/admin'
import { COURSE_STATUS, COURSE_TYPE_OPTIONS } from '@/types/admin'
import {
  courseStatusTagType,
  formatDateTime,
  formatDuration,
  formatPrice,
  pageTotal,
} from '@/utils/format'

/**
 * 课程管理
 *
 * ★ 三个"业务动作"（上架 / 下架 / 删除）都不是普通 CRUD，所以都做了拦截：
 *   · **上架**：先调 `checkBeforeUpShelf` 让**后端**说能不能上 ——
 *     前端不自己列条件（那样迟早和后端规则不一致），拿不到"可以"就不给上
 *   · **下架**：会连带**删掉 ES 索引**（学员立刻搜不到）。必须说清影响再确认
 *   · **删除**：终态操作，要求输入课程名确认（只弹"确定吗"太容易误点）
 */
const loading = ref(false)
const rows = ref<CoursePageVO[]>([])
const total = ref(0)
const categories = ref<CategoryVO[]>([])
const acting = ref<string | null>(null)
const router = useRouter()

/**
 * ★ 判断一门课**能不能编辑 / 配题**。
 *
 * 依据是"数据在哪张表"：草稿表里的课（待上架 1 / 下架 3）才能编辑，
 * 而正式表里的（已上架 2 / 已完结 4）**改不了** ——
 * 因为建课接口全都读 `course_draft`，而 `upShelf` 已经把草稿删了。
 * 所以已上架的课要先"下架"（下架会把正式数据反向复制回草稿）再编辑。
 *
 * 这与"题目只存在于草稿态"是同一条约束的两面，所以"配题"用的是同一个判断。
 */
function canEdit(status: number | null): boolean {
  return status === COURSE_STATUS.DRAFT || status === COURSE_STATUS.OFF_SHELF
}

const query = reactive({
  pageNo: 1,
  pageSize: 10,
  name: '',
  courseType: undefined as number | undefined,
  /**
   * ★★ 「状态」是**必选**的，默认「已上架」。
   *
   * 为什么不能给个"全部"：`CourseController.queryForPage` 是**按状态路由到两张表**的：
   *   · status = 已上架(2) / 已完结(4) → 查 `course`（正式表）
   *   · 其它（含**未指定**）→ 查 `course_draft`（草稿表）
   * 而草稿在上架时会被删掉（M7-6 核实过：上架后草稿表 0 行），
   * 所以"不传状态"**恒定返回 0 条** —— 实测库里 11 门已上架课程，
   * 不带 status 查出来是 `total=0`，看着像"数据没了"。
   *
   * → 与其让前端假装能"全部"，不如把状态做成必选项并把原因写清楚。
   *   （要真做"全部"，得前端两次查询合并 —— 但那样 `total` 就不是后端给的了，
   *    分页会错，属于用一个更糟的问题换一个更好的观感。）
   */
  status: 2 as number,
  firstCateId: undefined as string | undefined,
})

async function load() {
  loading.value = true
  try {
    const page = await getCoursePage(query)
    rows.value = page?.list ?? []
    total.value = pageTotal(page)
  } catch {
    /* request.ts 已提示 */
  } finally {
    loading.value = false
  }
}

function resetQuery() {
  Object.assign(query, {
    pageNo: 1,
    name: '',
    courseType: undefined,
    status: 2, // ★ 回到默认的「已上架」，而不是 undefined（undefined 会落到草稿表 → 0 条）
    firstCateId: undefined,
  })
  void load()
}

/**
 * 上架
 *
 * ★ 为什么把"校验"单独提出来先调一次（而不是直接 `upShelf` 然后看报错）：
 *   `upShelf` 也会校验，但它是**复合动作**（校验通过后就开始写正式表、发 MQ、建索引）。
 *   先调一次纯校验接口，等于把"能不能上"和"上"分开 ——
 *   失败时用户看到的是"原因"，而不是"上架失败"四个字。
 */
async function handleUpShelf(row: CoursePageVO) {
  acting.value = row.id
  try {
    try {
      await checkBeforeUpShelf(row.id)
    } catch (e) {
      // ★ 校验失败是**业务码非 200**（HTTP 200），不是抛异常到 catch 之外
      const msg = e instanceof Error ? e.message : String(e)
      ElMessage.warning(`还不能上架：${msg}`)
      return
    }

    await ElMessageBox.confirm(
      `确认上架《${row.name}》？\n\n上架后学员即可搜索到并购买/报名。`,
      '上架确认',
      { type: 'warning', confirmButtonText: '确认上架', cancelButtonText: '再想想' },
    )
    await upShelf(row.id)
    ElMessage.success('已上架（索引同步是异步的，搜索里稍后就能查到）')
    await load()
  } catch {
    /* 用户取消 / request.ts 已提示 */
  } finally {
    acting.value = null
  }
}

/** 下架：会删 ES 索引，所以要说清影响 */
async function handleDownShelf(row: CoursePageVO) {
  acting.value = row.id
  try {
    await ElMessageBox.confirm(
      `确认下架《${row.name}》？\n\n下架后：\n· 学员端搜不到、也进不了详情\n· 已报名的学员不受影响\n· 搜索索引会被删除`,
      '下架确认',
      { type: 'warning', confirmButtonText: '确认下架', cancelButtonText: '取消' },
    )
    await downShelf(row.id)
    ElMessage.success('已下架')
    await load()
  } catch {
    /* 用户取消 / 已提示 */
  } finally {
    acting.value = null
  }
}

/**
 * 删除：要求输入课程名
 *
 * ★ 为什么不只用 `ElMessageBox.confirm`：列表里的行高亮很容易滑到相邻行，
 *   一个"确定/取消"点错的代价是**不可逆的数据丢失**。
 *   要求手打名字，等于强制用户再看一眼自己删的是哪门课。
 */
async function handleDelete(row: CoursePageVO) {
  acting.value = row.id
  try {
    await ElMessageBox.prompt(
      `删除是**不可逆**的，且会连带删除目录、题目绑定等数据。\n请输入课程名以确认：${row.name}`,
      '危险操作',
      {
        type: 'error',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        inputPattern: new RegExp(`^${row.name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`),
        inputErrorMessage: '课程名不匹配',
      },
    )
    await deleteCourse(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 用户取消 / 已提示 */
  } finally {
    acting.value = null
  }
}

onMounted(async () => {
  await load()
  try {
    categories.value = (await getCategoryTree(1)) ?? []
  } catch {
    /* 分类拉不到不影响主流程 */
  }
})
</script>

<template>
  <div class="fm-page course-admin">
    <!-- 筛选 -->
    <el-card shadow="never" class="filter-card">
      <div class="filter-row">
        <el-input
          v-model="query.name"
          placeholder="按课程名搜索"
          clearable
          style="width: 200px"
          data-testid="course-filter-name"
          @keyup.enter="query.pageNo = 1; load()"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>

        <el-select
          v-model="query.firstCateId"
          placeholder="训练目标"
          clearable
          style="width: 150px"
          data-testid="course-filter-cate"
        >
          <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>

        <el-select
          v-model="query.courseType"
          placeholder="课程类型"
          clearable
          style="width: 140px"
          data-testid="course-filter-type"
        >
          <el-option v-for="t in COURSE_TYPE_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>

        <el-select
          v-model="query.status"
          placeholder="状态"
          style="width: 130px"
          data-testid="course-filter-status"
          @change="query.pageNo = 1; load()"
        >
          <el-option label="已上架" :value="COURSE_STATUS.ON_SHELF" />
          <el-option label="已完结" :value="COURSE_STATUS.FINISHED" />
          <el-option label="待上架（草稿）" :value="COURSE_STATUS.DRAFT" />
          <el-option label="下架（草稿）" :value="COURSE_STATUS.OFF_SHELF" />
        </el-select>

        <el-button type="primary" :icon="Search" @click="query.pageNo = 1; load()">查询</el-button>
        <el-button :icon="Refresh" @click="resetQuery">重置</el-button>

        <!-- ★ M7-12 第 2 期：建课入口（原来只能看/上下架，不能新建） -->
        <el-button
          type="success"
          :icon="Plus"
          data-testid="course-new"
          @click="router.push({ name: 'course-edit' })"
        >
          新建课程
        </el-button>

        <span class="total-hint" data-testid="course-total">共 {{ total }} 门</span>
      </div>

      <div class="hint-line">
        ★★ 「状态」是**必选**的：后端按状态把查询**路由到两张表** ——
        已上架 / 已完结 → `course`（正式表）；待上架 / 下架 / **未指定** → `course_draft`（草稿表）。
        而草稿在上架时会被删掉，所以"不传状态"会**恒定返回 0 条**（库里 11 门已上架课程也查不到）。
        这也是这里**没有「全部」选项**的原因。
        <br />
        ★ 另外：`CoursePageQuery.source`（draft / shelf）这个参数**后端并没有使用**
        （两个实现里都搜不到），属于死参数，所以前端也不提供它对应的控件。
      </div>
    </el-card>

    <!-- 列表 -->
    <el-card shadow="never">
      <el-table
        v-loading="loading"
        :data="rows"
        stripe
        row-key="id"
        data-testid="course-table"
        empty-text="没有符合条件的课程"
      >
        <el-table-column label="课程" min-width="240">
          <template #default="{ row }">
            <div class="course-cell">
              <CoverThumb :src="row.coverUrl" :name="row.name" :seed="row.id" :width="76" :height="48" />
              <div class="course-meta">
                <div class="course-name" data-testid="course-name">{{ row.name }}</div>
                <div class="course-sub">id={{ row.id }} · 更新 {{ formatDateTime(row.updateTime) }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            {{ COURSE_TYPE_OPTIONS.find((t) => t.value === row.courseType)?.label ?? '—' }}
          </template>
        </el-table-column>

        <el-table-column label="价格" width="100">
          <template #default="{ row }">{{ formatPrice(row.price, row.free) }}</template>
        </el-table-column>

        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-tag :type="courseStatusTagType(row.status)" size="small" effect="light">
              {{ row.statusDesc ?? '—' }}
            </el-tag>
            <!-- 草稿态才显示"填写进度"：已上架的课 step 恒为完成，显示反而是噪音 -->
            <el-tag v-if="row.status === COURSE_STATUS.DRAFT" class="step-tag" size="small" type="info">
              step {{ row.step ?? 0 }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="内容" width="150">
          <template #default="{ row }">
            <span>{{ row.sectionNum ?? 0 }} 个动作</span>
            <br />
            <span class="course-sub">{{ formatDuration(row.mediaDuration) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="上架" width="90">
          <template #default="{ row }">
            <span>{{ row.publishTimes ?? 0 }} 次</span>
            <br />
            <span class="course-sub">{{ row.publishTime ? formatDateTime(row.publishTime).slice(5) : '未上架' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="340" fixed="right">
          <template #default="{ row }">
            <!--
              ★★ 编辑 / 配题只在**草稿态**的课上可用（待上架 / 下架）。
                 已上架与已完结的课给的是**禁用态 + 原因**，而不是干脆藏起来：
                 藏起来用户会以为"这个页面没这功能"，禁用 + tooltip 才说得清"为什么现在不行"。
            -->
            <el-tooltip
              v-if="canEdit(row.status)"
              content="继续填写建课向导（5 步：基本信息/目录/视频/小测/教练）"
              placement="top"
            >
              <el-button
                link
                type="primary"
                :icon="EditPen"
                data-testid="course-edit"
                @click="router.push({ name: 'course-edit-id', params: { id: row.id } })"
              >
                继续填写
              </el-button>
            </el-tooltip>
            <el-tooltip
              v-else
              content="已上架 / 已完结的课程要先「下架」才能编辑（建课接口只读草稿表）"
              placement="top"
            >
              <span>
                <el-button link disabled :icon="EditPen" data-testid="course-edit-disabled">
                  继续填写
                </el-button>
              </span>
            </el-tooltip>

            <el-tooltip
              v-if="canEdit(row.status)"
              content="给训练动作配小测（题目只存在于草稿态，上架后不可改）"
              placement="top"
            >
              <el-button
                link
                type="primary"
                :icon="Collection"
                data-testid="course-subjects"
                @click="router.push({ name: 'subjects', query: { courseId: row.id } })"
              >
                配题
              </el-button>
            </el-tooltip>

            <el-button
              v-if="row.status !== COURSE_STATUS.ON_SHELF"
              link
              type="primary"
              :icon="Upload"
              :loading="acting === row.id"
              data-testid="course-uplift"
              @click="handleUpShelf(row as CoursePageVO)"
            >
              上架
            </el-button>
            <el-button
              v-else
              link
              type="warning"
              :icon="Download"
              :loading="acting === row.id"
              data-testid="course-downshelf"
              @click="handleDownShelf(row as CoursePageVO)"
            >
              下架
            </el-button>
            <el-button link type="danger" :icon="Delete" data-testid="course-delete" @click="handleDelete(row as CoursePageVO)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNo"
        v-model:page-size="query.pageSize"
        class="pager"
        layout="total, prev, pager, next, sizes"
        :total="total"
        :page-sizes="[10, 20, 50]"
        data-testid="course-pager"
        @current-change="load"
        @size-change="query.pageNo = 1; load()"
      />
    </el-card>
  </div>
</template>

<style scoped>
.course-admin {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.filter-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.total-hint {
  margin-left: auto;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.hint-line {
  margin-top: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.7;
}

.course-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.course-meta {
  min-width: 0;
}

.course-name {
  font-weight: 600;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.course-sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.step-tag {
  margin-left: 6px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
