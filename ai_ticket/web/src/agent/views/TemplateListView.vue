<template>
  <div>
    <el-card shadow="never">
      <div class="bar">
        <span class="title">模板</span>
        <span class="tip">模板 = 领域配置包：角色 + 知识 + 决策项 + 字段</span>
        <el-button type="primary" class="right" @click="$router.push('/templates/new')">
          新建模板
        </el-button>
        <el-button @click="load" :loading="loading">刷新</el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="card">
      <el-table :data="rows" v-loading="loading" size="small"
                @row-click="(row) => $router.push(`/templates/${row.id}`)"
                style="cursor: pointer">
        <el-table-column prop="id" label="#" width="70" />
        <el-table-column prop="name" label="名称" min-width="200" />
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column prop="fieldCount" label="字段数" width="90" />
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? '是' : '否' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="180" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { listTemplates } from '../../shared/api/template'

const rows = ref([])
const loading = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  try {
    rows.value = await listTemplates()
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: 12px;
}
.title {
  font-size: 16px;
  font-weight: 700;
}
.tip {
  color: #909399;
  font-size: 13px;
}
.right {
  margin-left: auto;
}
.card {
  margin-top: 16px;
}
</style>
