<template>
  <div v-loading="loading">
    <el-page-header :content="isNew ? '新建模板' : `编辑模板 #${id}`"
                    @back="$router.push('/templates')" />

    <el-row :gutter="16">
      <el-col :span="15">
        <el-card shadow="never" class="card">
          <template #header>
            <span>模板配置</span>
            <span class="tip">整体提交：改完一次性保存，后端不做字段级更新</span>
          </template>

          <el-form label-width="90px" size="small">
            <el-form-item label="名称">
              <el-input v-model="form.name" placeholder="电商售后工单" />
            </el-form-item>
            <el-form-item label="角色">
              <el-input v-model="form.role" type="textarea" :rows="2"
                        placeholder="你是服装店的售后助手，语气克制，不承诺赔付…" />
            </el-form-item>
            <el-form-item label="决策选项">
              <el-input :model-value="decisionText"
                        placeholder="批准换货,批准退货,拒绝"
                        @update:model-value="(v) => (form.decisionOptions = splitList(v))" />
            </el-form-item>
            <el-form-item label="领域知识">
              <el-input v-model="form.knowledge" type="textarea" :rows="5"
                        placeholder="【退换货政策】…【尺码表】…（全量注入 prompt，不检索）" />
            </el-form-item>
          </el-form>

          <el-divider content-position="left">
            字段定义（必填/选填必须显式声明）
          </el-divider>
          <FieldEditTable :fields="form.fields" />

          <div class="actions">
            <el-button type="primary" :loading="saving" @click="save">保存模板</el-button>
            <span class="tip">保存成功后版本号 +1</span>
          </div>
        </el-card>
      </el-col>

      <el-col :span="9">
        <el-card shadow="never" class="card">
          <template #header>
            <span>试抽</span>
            <span class="tip">跑一次完整抽取 + 核验，不落库</span>
          </template>

          <el-input v-model="dialogue" type="textarea" :rows="5"
                    placeholder="粘一段顾客原话，例如：订单号 2026100812345678，收到的 M 码太大，想换 S" />
          <el-button class="preview-btn" type="primary" plain
                     :loading="previewing" :disabled="isNew || !dialogue.trim()"
                     @click="runPreview">
            试抽
          </el-button>
          <div v-if="isNew" class="tip">先保存模板才能试抽</div>

          <template v-if="preview">
            <el-divider />
            <QualityBanner :level="preview.quality?.level" :messages="preview.quality?.messages || []" />
            <DynamicForm
              :fields="previewFields"
              :values="preview.values || {}"
              :basis="preview.basis || {}"
            />
            <div class="metrics">
              <div>
                <span class="k">必填满足率</span>
                <span class="v">{{ pct(preview.quality?.metrics?.requiredRate) }}</span>
              </div>
              <div>
                <span class="k">依据覆盖率</span>
                <span class="v">{{ pct(preview.quality?.metrics?.basisCoverage) }}</span>
              </div>
            </div>
          </template>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import FieldEditTable from '../../shared/components/FieldEditTable.vue'
import DynamicForm from '../../shared/components/DynamicForm.vue'
import QualityBanner from '../../shared/components/QualityBanner.vue'
import { getTemplate, createTemplate, updateTemplate, previewTemplate } from '../../shared/api/template'

const route = useRoute()
const router = useRouter()

const id = computed(() => route.params.id)
const isNew = computed(() => id.value === 'new')

const loading = ref(false)
const saving = ref(false)
const previewing = ref(false)
const dialogue = ref('')
const preview = ref(null)

const form = reactive({
  name: '',
  role: '',
  knowledge: '',
  decisionOptions: [],
  fields: []
})

const decisionText = computed(() => form.decisionOptions.join(','))

// 试抽结果里没有 schema，用当前正在编辑的字段来渲染（所见即所得）
const previewFields = computed(() =>
  form.fields.map((f) => ({ ...f, options: f.options }))
)

onMounted(load)

async function load() {
  if (isNew.value) {
    form.fields = [{ key: '', label: '', type: 'text', required: false, options: null, pattern: null }]
    return
  }
  loading.value = true
  try {
    const data = await getTemplate(id.value)
    form.name = data.name
    form.role = data.role
    form.knowledge = data.knowledge || ''
    form.decisionOptions = data.decisionOptions || []
    // 复制一份，避免直接编辑响应对象
    form.fields = (data.fields || []).map((f) => ({ ...f }))
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.name.trim() || !form.role.trim()) {
    ElMessage.warning('名称与角色不能为空')
    return
  }
  saving.value = true
  try {
    const body = {
      name: form.name,
      role: form.role,
      knowledge: form.knowledge,
      decisionOptions: form.decisionOptions,
      fields: form.fields
    }
    if (isNew.value) {
      const created = await createTemplate(body)
      ElMessage.success('已创建')
      router.replace(`/templates/${created.id}`)
    } else {
      const updated = await updateTemplate(id.value, body)
      ElMessage.success(`已保存，当前版本 v${updated.version}`)
      preview.value = null
    }
  } finally {
    saving.value = false
  }
}

async function runPreview() {
  previewing.value = true
  try {
    preview.value = await previewTemplate(id.value, dialogue.value)
  } finally {
    previewing.value = false
  }
}

function splitList(v) {
  return (v || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
}

function pct(v) {
  if (v === null || v === undefined) return '—'
  return (Number(v) * 100).toFixed(0) + '%'
}
</script>

<style scoped>
.card {
  margin-top: 16px;
}
.tip {
  margin-left: 10px;
  font-size: 12px;
  color: #909399;
}
.actions {
  margin-top: 18px;
  display: flex;
  align-items: center;
}
.preview-btn {
  margin-top: 12px;
  width: 100%;
}
.metrics {
  margin-top: 12px;
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
  font-weight: 600;
}
</style>
