<template>
  <div class="page">
    <!-- 统计只按当前列表算：多调一个 count 接口，数字就可能和表格里的行对不上 -->
    <div class="stats">
      <span>共 <b>{{ rows.length }}</b> 条</span>
      <i class="sep">/</i>
      <span>不合规 <b class="red">{{ redCount }}</b> 条</span>
      <i class="sep">/</i>
      <span>待入账 <b class="amber">{{ pendingCount }}</b> 条</span>
      <span class="tip">点任意一行查看详情并操作</span>
    </div>

    <el-card shadow="never" class="filters">
      <div class="bar">
        <el-date-picker v-model="filters.dateFrom" type="date" value-format="YYYY-MM-DD"
                        placeholder="进货日期起" clearable style="width: 130px" />
        <span class="tilde">~</span>
        <el-date-picker v-model="filters.dateTo" type="date" value-format="YYYY-MM-DD"
                        placeholder="进货日期止" clearable style="width: 130px" />

        <el-input v-model="filters.supplierName" placeholder="供货者名称" clearable
                  style="width: 132px" @keyup.enter="load" />

        <el-select v-model="filters.categoryId" placeholder="全部品类" clearable style="width: 104px">
          <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
          <!-- 老数据没有品类；不给这个入口，它们在任何品类筛选下都看不见 -->
          <el-option label="未分类" :value="-1" />
        </el-select>

        <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 104px">
          <el-option v-for="o in STATUS_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>

        <el-select v-model="filters.level" placeholder="全部级别" clearable style="width: 104px">
          <el-option label="不合规" value="RED" />
          <el-option label="待补证" value="YELLOW" />
          <el-option label="记录完整" value="GREEN" />
        </el-select>

        <el-input v-model="filters.keyword" placeholder="名称 / 供货者 / 批号" clearable
                  style="width: 160px" @keyup.enter="load" />

        <el-checkbox v-model="filters.expiring">只看临期</el-checkbox>

        <!-- 三个动作按钮包成一组：否则筛选占满一行时，导出按钮会单独掉到第二行 -->
        <div class="actions">
          <el-button type="primary" :loading="loading" @click="load">刷新</el-button>
          <el-button :disabled="loading" @click="reset">重置</el-button>
          <el-button type="success" plain :disabled="loading || !rows.length"
                     @click="exportExcel">
            导出 Excel
          </el-button>
        </div>
      </div>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table v-loading="loading" :data="rows" size="small" row-key="id"
                empty-text="没有符合条件的台账" style="cursor: pointer"
                @row-click="toDetail">
        <el-table-column prop="id" label="#" width="58" />
        <el-table-column prop="purchaseDate" label="进货日期" width="104" />
        <el-table-column prop="ingredientName" label="名称" min-width="110" show-overflow-tooltip />
        <el-table-column prop="spec" label="规格" width="94" show-overflow-tooltip />
        <el-table-column label="数量" width="92">
          <template #default="{ row }">
            <span v-if="row.quantity !== null && row.quantity !== undefined">
              {{ row.quantity }} {{ row.unit || '' }}
            </span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="batchNo" label="批号" min-width="118" show-overflow-tooltip />
        <el-table-column prop="supplierName" label="供货者" min-width="128" show-overflow-tooltip />
        <el-table-column label="金额" width="98" align="right">
          <template #default="{ row }">
            <span v-if="row.amount !== null && row.amount !== undefined">{{ money(row.amount) }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="级别" width="96">
          <template #default="{ row }">
            <GradeTag :level="row.qualityLevel" />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="statusType(row.status)">
              {{ statusText(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="到期" width="112">
          <template #default="{ row }">
            <span v-if="row.expiryDaysLeft === null || row.expiryDaysLeft === undefined"
                  class="muted">—</span>
            <span v-else :class="expiryClass(row.expiryDaysLeft)">
              {{ expiryText(row.expiryDaysLeft) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="76" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click.stop="toDetail(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="load"
          @size-change="onSizeChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import GradeTag from '../../shared/components/GradeTag.vue'
import { listCategories, listLedgerPage } from '../../shared/api/ledger'

/*
 * 台账列表：管理员的入口页。
 *
 * 筛选条件全部原样交给后端（GET /ledger 的查询参数），前端只做三件事：
 * 把空值剔掉（空字符串会被当成过滤条件）、把「到期还剩几天」翻译成人话、
 * 顺手统计当前结果里的不合规与待入账条数。
 *
 * 不做分页：台账是年度几百条的规模，一屏滚动比翻页更好核对；
 * 真到需要分页的量级，再让后端加 total 和 page 更合适。
 */
const router = useRouter()

const STATUS_TEXT = {
  PENDING: '待入账',
  POSTED: '已入账',
  RETURNED: '退回补录',
  VOID: '已作废'
}
const STATUS_OPTIONS = Object.entries(STATUS_TEXT).map(([value, label]) => ({ value, label }))

const rows = ref([])
const categories = ref([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const size = ref(20)

const filters = reactive({
  dateFrom: '',
  dateTo: '',
  supplierName: '',
  categoryId: null,
  status: '',
  level: '',
  keyword: '',
  expiring: false
})

const redCount = computed(() => rows.value.filter((r) => r.qualityLevel === 'RED').length)
const pendingCount = computed(() => rows.value.filter((r) => r.status === 'PENDING').length)

onMounted(() => {
  loadCategories()
  load()
})

async function loadCategories() {
  try {
    categories.value = (await listCategories()) || []
  } catch (e) {
    // 品类下拉拿不到不该堵住列表：台账本身还能按别的条件查
    categories.value = []
  }
}

/** 空值不传：后端把 null 当「不过滤」，传空字符串却会变成 like '%%' 之外的噪音 */
function buildParams() {
  const params = {}
  if (filters.dateFrom) params.dateFrom = filters.dateFrom
  if (filters.dateTo) params.dateTo = filters.dateTo
  if (filters.supplierName.trim()) params.supplierName = filters.supplierName.trim()
  if (filters.categoryId) params.categoryId = filters.categoryId
  if (filters.status) params.status = filters.status
  if (filters.level) params.level = filters.level
  if (filters.keyword.trim()) params.keyword = filters.keyword.trim()
  if (filters.expiring) params.expiring = true
  return params
}

async function load() {
  loading.value = true
  try {
    const result = await listLedgerPage({ ...buildParams(), page: page.value, size: size.value })
    rows.value = result.rows || []
    total.value = result.total || 0
  } finally {
    loading.value = false
  }
}

/**
 * 导出 Excel。
 *
 * 直接复用界面上这套筛选条件拼 query，而不是自己再写一份：
 * 否则迟早出现「看到 20 条、导出 200 条」。
 * 用 location 跳转而不是 axios，是因为浏览器要拿它当附件下载——
 * 让浏览器处理 Content-Disposition，比在 JS 里造 Blob 更省事、也更不容易出错。
 */
function exportExcel() {
  // 和列表用同一个 buildParams()：界面上筛出什么，导出的就是什么
  const params = new URLSearchParams(buildParams())
  window.open('/api/ledger/export?' + params.toString(), '_blank')
}

function onSizeChange() {
  page.value = 1
  load()
}

/**
 * 筛选实时生效。
 *
 * 为什么加 350ms 防抖：
 * 输入框每敲一个字就请求一次，既浪费又会乱序（后发的先回）；
 * 350ms 是不打字之后的感觉延迟阈值附近，看上去仍是"即时"。
 * 日期与下拉是瞬时选择，也走同一条路径，不必各写一套。
 */
let filterTimer = null
watch(
  () => ({ ...filters }),
  () => {
    clearTimeout(filterTimer)
    filterTimer = setTimeout(() => {
      page.value = 1
      load()
    }, 350)
  },
  { deep: true }
)

function reset() {
  filters.dateFrom = ''
  filters.dateTo = ''
  filters.supplierName = ''
  filters.categoryId = null
  filters.status = ''
  filters.level = ''
  filters.keyword = ''
  filters.expiring = false
  page.value = 1
  load()
}

function toDetail(row) {
  router.push(`/ledger/${row.id}`)
}

/** 状态文字优先用本地映射：报表要打印，不能只靠颜色 */
function statusText(row) {
  return STATUS_TEXT[row.status] || row.statusLabel || row.status || '—'
}

function statusType(status) {
  if (status === 'POSTED') return 'success'
  if (status === 'RETURNED') return 'warning'
  if (status === 'VOID') return 'info'
  // 待入账用默认色：el-tag 的 type 不接受空字符串，会报 prop 校验警告
  return 'primary'
}

function expiryText(days) {
  return days < 0 ? `过期 ${-days} 天` : `剩 ${days} 天`
}

function expiryClass(days) {
  if (days < 0) return 'danger'
  if (days <= 3) return 'warn'
  return 'later'
}

function money(value) {
  const n = Number(value)
  return Number.isFinite(n) ? `¥ ${n.toFixed(2)}` : String(value)
}
</script>

<style scoped>
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }

.actions { display: flex; gap: 10px; margin-left: auto; }

.page { width: 100%; }

.stats {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 0 2px 10px; font-size: 13px; color: var(--text-body);
}
.stats b { font-size: 15px; margin: 0 1px; }
.stats .red { color: var(--red); }
.stats .amber { color: var(--yellow); }
.stats .sep { color: #cdd6df; font-style: normal; }
.stats .tip { margin-left: auto; font-size: 12px; color: var(--text-muted); }

.filters :deep(.el-card__body) { padding: 10px 12px; }
.bar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.tilde { color: #c0c4cc; }

.table-card { margin-top: 12px; }
.table-card :deep(.el-card__body) { padding: 4px 0 0; }

.muted { color: #c0c4cc; }
.later { color: var(--text-muted); }
.warn { color: var(--yellow); font-weight: 600; }
.danger { color: var(--red); font-weight: 600; }
</style>
