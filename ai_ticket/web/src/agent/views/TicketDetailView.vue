<template>
  <div v-loading="loading">
    <el-page-header content="工单详情" @back="$router.push('/tickets')" />

    <template v-if="ticket">
      <el-card shadow="never" class="card">
        <div class="head">
          <span class="id">#{{ ticket.id }}</span>
          <el-tag :type="tagOf(ticket.qualityLevel)" size="small">
            {{ levelLabel(ticket.qualityLevel) }}
          </el-tag>
          <el-tag size="small" effect="plain">{{ statusLabel(ticket.status) }}</el-tag>
          <el-tag v-if="ticket.needsClarify" type="danger" size="small">需回问顾客</el-tag>
          <el-tag v-if="ticket.customerConfirmed" type="success" size="small" effect="plain">
            顾客已确认
          </el-tag>
          <span class="meta">
            模板 v{{ ticket.templateVersion }} · {{ ticket.createdAt }}
            <template v-if="ticket.lastActor"> · 最后修改：{{ ticket.lastActor }}</template>
            · 版本 v{{ ticket.version }}
            <template v-if="ticket.revision > 1"> · 内容修订 r{{ ticket.revision }}</template>
            <template v-if="ticket.returnCount > 0"> · 已打回 {{ ticket.returnCount }} 次</template>
          </span>
        </div>
        <div class="summary">{{ ticket.summary || '（无摘要）' }}</div>
      </el-card>

      <QualityBanner
        class="card"
        :level="ticket.qualityLevel"
        :messages="ticket.qualityDetail?.messages || []"
      />

      <el-row :gutter="16">
        <el-col :span="14">
          <el-card shadow="never">
            <template #header>
              <span>工单内容</span>
              <span class="tip">依据常驻显示：绿色只说明信息齐全，不说明判断正确</span>
            </template>
            <DynamicForm
              :fields="ticket.schemaSnapshot?.fields || []"
              :values="ticket.values || {}"
              :basis="ticket.basis || {}"
            />
            <div class="suggestion">
              模型建议：
              <el-tag v-if="ticket.suggestion" size="small" type="success">{{ ticket.suggestion }}</el-tag>
              <span v-else class="muted">无（信息不足时不采用模型建议）</span>
            </div>
          </el-card>
        </el-col>

        <el-col :span="10">
          <el-card shadow="never">
            <template #header>决策</template>
            <el-form label-width="80px" size="small">
              <el-form-item label="状态">
                <el-select v-model="form.status" style="width: 100%">
                  <el-option v-for="s in STATUSES" :key="s.value" :label="s.label" :value="s.value" />
                </el-select>
              </el-form-item>
              <el-form-item label="负责人">
                <el-input v-model="form.assignee" placeholder="张三" />
              </el-form-item>
              <el-form-item label="决定">
                <el-input v-model="form.decision" placeholder="批准换货" />

          <label>打回说明</label>
          <el-input
            v-model="form.returnNote"
            type="textarea"
            :rows="2"
            placeholder="选「退回补全」时给顾客的话；不填则由系统按缺失字段自动追问"
          />
              </el-form-item>
              <el-form-item label="需回问">
                <el-switch v-model="form.needsClarify" />
                <span class="tip inline">这是回问率的埋点</span>
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="saving" @click="save">保存</el-button>
              </el-form-item>
            </el-form>
            <el-alert
              v-if="ticket.decisionTimeMs !== null && ticket.decisionTimeMs !== undefined"
              type="info"
              :closable="false"
              :title="`决策耗时 ${ticket.decisionTimeMs} ms`"
            />
          </el-card>

          <el-card shadow="never" class="card">
            <template #header>质量明细</template>
            <div class="metrics">
              <div v-for="(v, k) in metricsRows" :key="k">
                <span class="k">{{ k }}</span>
                <span class="v">{{ v }}</span>
              </div>
            </div>
            <el-divider />
            <div class="metrics">
              <div>
                <span class="k">缺必填</span>
                <span class="v">{{ (ticket.qualityDetail?.decision?.missingRequired || []).length }}</span>
              </div>
              <div>
                <span class="k">缺选填</span>
                <span class="v">{{ (ticket.qualityDetail?.decision?.missingOptional || []).length }}</span>
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <el-card shadow="never" class="card">
        <template #header>原始对话</template>
        <div v-for="(m, i) in conversation" :key="i" class="msg" :class="m.role">
          <span class="role">{{ m.role === 'user' ? '顾客' : '助手' }}</span>
          <span class="content">{{ m.content }}</span>
        </div>
        <el-empty v-if="!conversation.length" description="没有对话记录" :image-size="60" />
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import QualityBanner from '../../shared/components/QualityBanner.vue'
import DynamicForm from '../../shared/components/DynamicForm.vue'
import { getTicket, patchTicket } from '../../shared/api/ticket'
import { getHistory } from '../../shared/api/chat'

const STATUSES = [
  { value: 'PENDING', label: '待处理' },
  { value: 'INFO_INCOMPLETE', label: '信息不全' },
  { value: 'ASSIGNED', label: '已指派' },
  { value: 'PROCESSING', label: '处理中' },
  { value: 'RETURNED', label: '退回补全' },
  { value: 'RESOLVED', label: '已解决' },
  { value: 'CLOSED', label: '已关闭' }
]

const route = useRoute()
const ticket = ref(null)
const conversation = ref([])
const loading = ref(false)
const saving = ref(false)

const form = reactive({
  status: '',
  assignee: '',
  decision: '',
  needsClarify: false,
  returnNote: ''
})

const metricsRows = computed(() => {
  const m = ticket.value?.qualityDetail?.metrics || {}
  return {
    字段填充率: pct(m.fillRate),
    必填满足率: pct(m.requiredRate),
    选填覆盖率: pct(m.optionalCoverage),
    依据覆盖率: pct(m.basisCoverage)
  }
})

onMounted(load)

async function load() {
  loading.value = true
  try {
    ticket.value = await getTicket(route.params.id)
    form.status = ticket.value.status
    form.assignee = ticket.value.assignee || ''
    form.decision = ticket.value.decision || ''
    form.needsClarify = !!ticket.value.needsClarify
    form.returnNote = ticket.value.returnNote || ''
    if (ticket.value.sessionId) {
      conversation.value = await getHistory(ticket.value.sessionId)
    }
  } finally {
    loading.value = false
  }
}

/**
 * 保存。必须带上读到的 version ——
 * 不带的话后端直接 400（拒绝无锁更新），
 * 带了但是旧版本则 409（别人已经改过，先看最新再决定）。
 */
async function save() {
  saving.value = true
  try {
    ticket.value = await patchTicket(ticket.value.id, {
      version: ticket.value.version,
      status: form.status,
      assignee: form.assignee,
      decision: form.decision,
      needsClarify: form.needsClarify,
      returnNote: form.returnNote || null
    })
    ElMessage.success('已保存')
  } catch (e) {
    if (e.code === 409) {
      // 冲突不是错误操作，是「你手上这份旧了」：直接拉最新的给他看
      await load()
      ElMessage.warning('这张工单刚被别人改过，已为你刷新到最新内容，请重新确认后再保存')
    }
  } finally {
    saving.value = false
  }
}

function pct(v) {
  if (v === null || v === undefined) return '—'
  return (Number(v) * 100).toFixed(0) + '%'
}

function tagOf(level) {
  return { RED: 'danger', YELLOW: 'warning', GREEN: 'success' }[level] || 'info'
}

function levelLabel(level) {
  return { RED: '信息不全', YELLOW: '待核实', GREEN: '可直接决策' }[level] || level
}

function statusLabel(value) {
  return STATUSES.find((s) => s.value === value)?.label || value
}
</script>

<style scoped>
.card {
  margin-top: 16px;
}
.head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.id {
  font-size: 18px;
  font-weight: 700;
}
.meta {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
}
.summary {
  margin-top: 10px;
  font-size: 14px;
  color: #303133;
  line-height: 1.6;
}
.tip {
  margin-left: 12px;
  font-size: 12px;
  color: #909399;
}
.tip.inline {
  margin-left: 8px;
}
.suggestion {
  margin-top: 14px;
  font-size: 14px;
  color: #606266;
}
.muted {
  color: #c0c4cc;
}
.metrics div {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  padding: 3px 0;
}
.metrics .k {
  color: #909399;
}
.metrics .v {
  color: #303133;
  font-weight: 600;
}
.msg {
  display: flex;
  gap: 10px;
  padding: 6px 0;
  font-size: 13px;
  border-bottom: 1px dashed #f2f6fc;
}
.msg .role {
  width: 40px;
  flex-shrink: 0;
  color: #909399;
}
.msg .content {
  color: #303133;
  white-space: pre-wrap;
}
</style>
