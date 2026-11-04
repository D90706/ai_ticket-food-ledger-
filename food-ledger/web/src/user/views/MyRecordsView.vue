<template>
  <div class="wrap">
    <el-card shadow="never">
      <div class="bar">
        <span class="title">我的台账记录</span>
        <el-select v-model="status" clearable placeholder="全部状态" style="width: 140px"
                   @change="load">
          <el-option label="待入账" value="PENDING" />
          <el-option label="已入账" value="POSTED" />
          <el-option label="退回补录" value="RETURNED" />
          <el-option label="已作废" value="VOID" />
        </el-select>
        <el-button :loading="loading" @click="load">刷新</el-button>
        <span class="total">共 {{ rows.length }} 条</span>
      </div>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table :data="rows" v-loading="loading" size="small"
                @row-click="openDetail" style="cursor: pointer">
        <el-table-column prop="id" label="#" width="64" />
        <el-table-column prop="purchaseDate" label="进货日期" width="110" />
        <el-table-column prop="ingredientName" label="名称" width="110" />
        <el-table-column prop="spec" label="规格" width="100" />
        <el-table-column label="数量" width="100">
          <template #default="{ row }">
            <span v-if="row.quantity">{{ row.quantity }} {{ row.unit || '' }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="supplierName" label="供货者" min-width="130" show-overflow-tooltip />
        <el-table-column label="级别" width="100">
          <template #default="{ row }">
            <GradeTag :level="row.qualityLevel" />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" effect="plain"
                    :type="row.status === 'RETURNED' ? 'warning' : 'info'">
              {{ row.statusLabel }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="到期" width="120">
          <template #default="{ row }">
            <span v-if="row.expiryDaysLeft === null || row.expiryDaysLeft === undefined"
                  class="muted">—</span>
            <span v-else :class="{ danger: row.expiryDaysLeft < 0, warn: row.expiryDaysLeft >= 0 && row.expiryDaysLeft <= 3 }">
              {{ row.expiryDaysLeft < 0 ? `过期 ${-row.expiryDaysLeft} 天` : `剩 ${row.expiryDaysLeft} 天` }}
            </span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 详情 / 补录 -->
    <el-drawer v-model="drawer" :title="detail ? `记录 #${detail.id}` : ''" size="620px">
      <div v-if="detail">
        <el-alert v-if="detail.qualityLevel === 'RED'" type="error" :closable="false"
                  title="缺法定项目，不能入账" :description="(detail.quality?.messages || []).join('；')" />
        <el-alert v-else-if="detail.qualityLevel === 'YELLOW'" type="warning" :closable="false"
                  :title="'待补证' + ((detail.quality?.messages || []).join('；') ? '：' + (detail.quality?.messages || []).join('；') : '')" />
        <el-alert v-else type="success" :closable="false" title="记录完整" />

        <div v-if="(detail.warnings || []).length" class="warns">
          <el-tag v-for="w in detail.warnings" :key="w" size="small" type="danger" effect="plain">
            {{ w }}
          </el-tag>
        </div>

        <div class="section-title">台账内容</div>
        <!--
          按模板渲染，不再写死字段清单。
          原来是一份 17 项的常量表，漏了品类、产地、冷链到货温度、农残检测报告号，
          而「扩展字段」又只渲染已经存在的键 —— 结果是这几个字段在补录界面上
          根本没有输入框，采购员永远补不到「记录完整」。
        -->
        <LedgerForm v-if="templateFields.length" v-model="values" :fields="templateFields"
                    :disabled="!editable" />
        <div v-else class="muted">正在加载字段定义…</div>

        <div class="section-title">索证索票照片</div>
        <div class="attach">
          <div v-for="(a, i) in attachments" :key="a.url" class="attach-item">
            <el-image v-if="isImage(a.url)" :src="a.url" :preview-src-list="[a.url]"
                      fit="cover" class="thumb" />
            <a v-else :href="a.url" target="_blank" class="thumb pdf">PDF</a>
            <span class="attach-name" :title="a.name">{{ a.name }}</span>
            <el-button v-if="editable" link type="danger" size="small" @click="removeAttachment(i)">
              删除
            </el-button>
          </div>
          <el-upload v-if="editable" :show-file-list="false" :http-request="doUpload"
                     accept="image/*,.pdf">
            <el-button size="small" :loading="uploading">上传照片</el-button>
          </el-upload>
          <span v-if="!attachments.length" class="muted">还没有附件</span>
        </div>

        <div v-if="basisKeys.length" class="section-title">原文依据</div>
        <div v-if="basisKeys.length" class="basis">
          <div v-for="k in basisKeys" :key="k" class="basis-row">
            <span class="basis-key">{{ k }}</span>
            <span class="basis-quote">「{{ detail.basis[k] }}」</span>
          </div>
        </div>

        <div class="actions">
          <el-button v-if="editable" type="primary" :loading="saving" @click="save">
            保存补录
          </el-button>
          <el-button @click="drawer = false">关闭</el-button>
          <span v-if="editable" class="save-tip">
            保存只更新内容，状态仍是「待入账」——入账由管理员操作
          </span>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import GradeTag from '../../shared/components/GradeTag.vue'
import LedgerForm from '../../shared/components/LedgerForm.vue'
import { getLedger, listLedger, updateLedgerFromValues } from '../../shared/api/ledger'
import { getDefaultTemplate } from '../../shared/api/template'
import { uploadFile } from '../../shared/api/upload'

const props = defineProps({
  staff: { type: Object, default: null },
  refreshKey: { type: Number, default: 0 }
})

// 模板字段 ↔ 记录里的驼峰属性。只有固定列需要这张表，
// 自定义字段（产地、冷链温度、农残报告）在 detail.extra 里，按 key 直接取。
const DETAIL_KEY = {
  ingredient_name: 'ingredientName',
  spec: 'spec',
  quantity: 'quantity',
  unit: 'unit',
  production_date: 'productionDate',
  batch_no: 'batchNo',
  shelf_life: 'shelfLife',
  purchase_date: 'purchaseDate',
  supplier_name: 'supplierName',
  supplier_address: 'supplierAddress',
  supplier_phone: 'supplierPhone',
  certificate: 'certificate',
  expiry_date: 'expiryDate',
  unit_price: 'unitPrice',
  amount: 'amount',
  warehouse: 'warehouse',
  receiver: 'receiver',
  category: 'categoryName'
}

const rows = ref([])
const status = ref('')
const loading = ref(false)
const drawer = ref(false)
const saving = ref(false)
const detail = ref(null)
const templateFields = ref([])
const values = ref({})

onMounted(async () => {
  try {
    const tpl = await getDefaultTemplate()
    templateFields.value = tpl.fields || []
  } catch {
    templateFields.value = []
  }
})

/** 把记录摊成「模板 key → 值」的表，给 LedgerForm 用 */
function buildValues(rec) {
  const out = {}
  const extra = rec.extra || {}
  for (const f of templateFields.value) {
    const camel = DETAIL_KEY[f.key]
    out[f.key] = camel ? (rec[camel] ?? null) : (extra[f.key] ?? null)
  }
  return out
}

const editable = computed(() =>
  detail.value && (detail.value.status === 'PENDING' || detail.value.status === 'RETURNED'))

const attachments = ref([])
const uploading = ref(false)

const extraKeys = computed(() => Object.keys(detail.value?.extra || {}))

/** 图片才预览，PDF 走链接——把 PDF 塞进 el-image 只会显示一个破图标 */
function isImage(url) {
  return /\.(jpg|jpeg|png|webp)$/i.test(url || '')
}

async function doUpload(options) {
  uploading.value = true
  try {
    const attachment = await uploadFile(options.file)
    attachments.value.push(attachment)
    ElMessage.success('已上传，记得点保存')
  } finally {
    uploading.value = false
  }
}

function removeAttachment(index) {
  attachments.value.splice(index, 1)
}

const basisKeys = computed(() => Object.keys(detail.value?.basis || {}))

watch(() => props.refreshKey, load)
load()

async function load() {
  if (!props.staff) return
  loading.value = true
  try {
    // scope=MINE：我录的 + 退回给我补的。
    // 只按 createdBy 查的话，「管理员录了一条被退回」的记录在采购员页面上不存在，
    // 那条记录就没人补得了。
    rows.value = await listLedger({
      createdBy: props.staff.displayName,
      scope: 'MINE',
      status: status.value || undefined
    })
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  detail.value = await getLedger(row.id)
  values.value = buildValues(detail.value)
  attachments.value = [...(detail.value.attachments || [])]
  drawer.value = true
}

async function save() {
  saving.value = true
  try {
    // 提交「模板 key → 值」的表：后端用同一个映射器转成 DTO，
    // 前端不再需要维护第二份字段口径
    const updated = await updateLedgerFromValues(detail.value.id, {
      ...values.value,
      attachments: attachments.value
    }, detail.value.version)
    detail.value = updated
    values.value = buildValues(updated)
    ElMessage.success('已保存')
    await load()
  } catch (e) {
    if (e.code === 409) {
      ElMessage.warning('这行台账刚被人改过，已刷新到最新内容，请重新确认')
      detail.value = await getLedger(detail.value.id)
      values.value = buildValues(detail.value)
    }
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.save-tip { font-size: 11px; color: var(--text-muted); margin-left: 6px; }
.attach { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.attach-item { display: flex; align-items: center; gap: 8px; border: 1px solid var(--line); border-radius: 6px; padding: 6px; }
.thumb { width: 46px; height: 46px; border-radius: 4px; display: block; }
.thumb.pdf { display: flex; align-items: center; justify-content: center; background: #f4f6f4; font-size: 12px; color: var(--text-muted); text-decoration: none; }
.attach-name { max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; color: var(--text-muted); }

.wrap { width: 100%; max-width: 1180px; }
.bar { display: flex; align-items: center; gap: 10px; }
.title { font-weight: 700; color: var(--text-main); margin-right: 6px; }
.total { margin-left: auto; font-size: 12px; color: var(--text-muted); }
.table-card { margin-top: 12px; }
.muted { color: #c0c4cc; }
.danger { color: var(--red); font-weight: 600; }
.warn { color: var(--yellow); font-weight: 600; }
.warns { margin: 10px 0; display: flex; gap: 6px; flex-wrap: wrap; }
.section-title { margin: 16px 0 8px; font-weight: 700; font-size: 13px; color: var(--text-main); }
.basis { border-top: 1px dashed var(--line); padding-top: 8px; }
.basis-row { display: flex; gap: 10px; padding: 4px 0; font-size: 12px; }
.basis-key { color: var(--text-muted); min-width: 96px; }
.basis-quote { color: #4a5568; }
.actions { margin-top: 18px; display: flex; gap: 10px; }
</style>
