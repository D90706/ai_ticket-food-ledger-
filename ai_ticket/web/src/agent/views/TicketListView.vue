<template>
  <div>
    <el-card shadow="never">
      <div class="bar">
        <span>状态</span>
        <el-select v-model="status" clearable placeholder="全部" style="width: 150px" @change="load">
          <el-option v-for="s in STATUSES" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <span>级别</span>
        <el-select v-model="level" clearable placeholder="全部" style="width: 130px" @change="load">
          <el-option label="信息不全" value="RED" />
          <el-option label="待核实" value="YELLOW" />
          <el-option label="可直接决策" value="GREEN" />
        </el-select>
        <el-checkbox v-model="onlyMine" @change="applyFilter">只看我的</el-checkbox>
        <el-button @click="load" :loading="loading">刷新</el-button>
        <span class="total">共 {{ visible.length }} 张</span>
      </div>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table :data="visible" v-loading="loading" size="small"
                @row-click="(row) => $router.push(`/tickets/${row.id}`)"
                style="cursor: pointer">
        <el-table-column prop="id" label="#" width="70" />
        <el-table-column label="级别" width="110">
          <template #default="{ row }">
            <el-tag :type="tagOf(row.qualityLevel)" size="small">{{ labelOf(row.qualityLevel) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="summary" label="摘要" min-width="260" show-overflow-tooltip />
        <el-table-column prop="assignee" label="负责人" width="110" />
        <el-table-column label="需回问" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.needsClarify" type="danger" size="small">是</el-tag>
            <span v-else class="muted">否</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !rows.length" description="还没有工单，先去「对话」页生成一张" />
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { listTickets } from '../../shared/api/ticket'
import { state as authState } from '../authStore'

const STATUSES = [
  { value: 'PENDING', label: '待处理' },
  { value: 'INFO_INCOMPLETE', label: '信息不全' },
  { value: 'ASSIGNED', label: '已指派' },
  { value: 'PROCESSING', label: '处理中' },
  { value: 'RESOLVED', label: '已解决' },
  { value: 'CLOSED', label: '已关闭' }
]

const rows = ref([])
const status = ref('')
const level = ref('')
const onlyMine = ref(false)
const loading = ref(false)

/** 「只看我的」在客户端过滤：工单量小，不值得为它加一个接口参数 */
const visible = computed(() => {
  const me = authState.agent?.displayName
  if (!onlyMine.value || !me) return rows.value
  return rows.value.filter((r) => r.assignee === me)
})

onMounted(load)

async function load() {
  loading.value = true
  try {
    rows.value = await listTickets({
      status: status.value || undefined,
      level: level.value || undefined
    })
  } finally {
    loading.value = false
  }
}

function tagOf(level) {
  return { RED: 'danger', YELLOW: 'warning', GREEN: 'success' }[level] || 'info'
}

function labelOf(level) {
  return { RED: '信息不全', YELLOW: '待核实', GREEN: '可直接决策' }[level] || level
}

function statusLabel(value) {
  return STATUSES.find((s) => s.value === value)?.label || value
}

/** 后端返回的是 ISO 串（2026-10-22T11:04:56.153），列表里只留到分钟 */
function fmt(v) {
  if (!v) return '—'
  return String(v).replace('T', ' ').slice(0, 16)
}
</script>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: 10px;
}
.total {
  margin-left: auto;
  color: #909399;
  font-size: 13px;
}
.table-card {
  margin-top: 16px;
}
.muted {
  color: #c0c4cc;
}
</style>
