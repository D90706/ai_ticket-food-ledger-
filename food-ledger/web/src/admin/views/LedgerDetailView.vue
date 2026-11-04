<template>
  <div class="page" v-loading="loading">
    <template v-if="detail">
      <!-- 头部：一行说清「这条是谁录的、什么状态、改过几次」 -->
      <el-card shadow="never" class="head-card">
        <div class="head">
          <div class="head-left">
            <el-button link class="back" @click="back">← 台账列表</el-button>
            <span class="id">#{{ detail.id }}</span>
            <GradeTag :level="detail.qualityLevel" />
            <el-tag size="small" effect="plain" :type="statusTagType">
              {{ statusText(detail.status, detail.statusLabel) }}
            </el-tag>
            <el-tooltip v-if="detail.revision > 1" placement="top"
                        content="内容修订号：补录保存一次加一，能看出这行改过几次">
              <span class="rev">r{{ detail.revision }}</span>
            </el-tooltip>
          </div>

          <div class="head-right">
            <span class="meta">录入人 <b>{{ detail.createdBy || '—' }}</b></span>
            <span class="meta">{{ formatTime(detail.createdAt) }}</span>
            <template v-if="detail.sourceType === 'AI'">
              <span class="source ai">AI 录入</span>
              <el-link v-if="detail.sourceSessionId" type="primary"
                       class="session" @click="showSession">
                会话 #{{ detail.sourceSessionId }}
              </el-link>
            </template>
            <span v-else class="source">手填</span>
          </div>
        </div>
      </el-card>

      <!-- 合规：结论 + 原因 + 提示。半年后有人问「为什么不让入账」，就在这一块 -->
      <el-card shadow="never" class="section">
        <el-alert :type="alertType" :title="alertTitle" :closable="false" show-icon>
          <div v-if="messages.length" class="msgs">
            <div v-for="(m, i) in messages" :key="i">{{ m }}</div>
          </div>
        </el-alert>

        <div v-if="warnings.length" class="warnings">
          <el-tag v-for="w in warnings" :key="w" type="warning" effect="plain" size="small">
            {{ w }}
          </el-tag>
        </div>
      </el-card>

      <!-- 台账内容：按记录自己的模板快照渲染，模板后来改过也不影响这一行 -->
      <el-card shadow="never" class="section">
        <div class="section-title">
          台账内容
          <span class="sub">按录入时的模板 v{{ detail.templateVersion }} 渲染，共 {{ fields.length }} 项</span>
        </div>

        <div class="grid">
          <div v-for="f in fields" :key="f.key" class="cell">
            <div class="label">
              {{ f.label }}
              <span v-if="f.required" class="req" title="必填">*</span>
              <span v-if="f.fixed" class="legal" title="法定项目">法定</span>
            </div>
            <div class="value" :class="{ missing: isMissing(f), blank: !isMissing(f) && isBlank(rawValue(f)) }">
              {{ displayValue(f) }}
            </div>
            <div v-if="basisOf(f.key)" class="basis">依据：「{{ basisOf(f.key) }}」</div>
            <div v-else-if="f.hint && isBlank(rawValue(f))" class="hint">{{ f.hint }}</div>
          </div>
        </div>
      </el-card>

      <!-- 操作：全部动作都带 version，抢在别人前面改同一行时宁可报冲突 -->
      <el-card shadow="never" class="section">
        <!-- 索证索票照片：检查时最常被要求出示的东西 -->
        <div class="section-title">索证索票附件</div>
        <div class="attach" v-if="detail.attachments && detail.attachments.length">
          <div v-for="a in detail.attachments" :key="a.url" class="attach-item">
            <el-image v-if="isImage(a.url)" :src="a.url" :preview-src-list="[a.url]"
                      fit="cover" class="thumb" />
            <a v-else :href="a.url" target="_blank" class="thumb pdf">PDF</a>
            <span class="attach-name" :title="a.name">{{ a.name }}</span>
          </div>
        </div>
        <div v-else class="muted attach-empty">没有上传附件（索证索票只记了凭证号）</div>

        <div class="section-title">审核操作</div>

        <div v-if="stateLines.length" class="state-lines">
          <div v-for="(line, i) in stateLines" :key="i" class="line">{{ line }}</div>
        </div>

        <div v-if="!isVoid" class="btns">
          <el-tooltip :disabled="!postBlocked" :content="postBlocked" placement="top">
            <span class="hold">
              <el-button type="primary" :disabled="!!postBlocked"
                         :loading="acting === 'post'" @click="doPost">入账</el-button>
            </span>
          </el-tooltip>

          <el-tooltip :disabled="!returnBlocked" :content="returnBlocked" placement="top">
            <span class="hold">
              <el-button :disabled="!!returnBlocked"
                         :loading="acting === 'return'" @click="doReturn">退回补录</el-button>
            </span>
          </el-tooltip>

          <el-button type="danger" plain :loading="acting === 'void'" @click="doVoid">作废</el-button>

          <span class="version">当前版本 v{{ detail.version }}（操作都带 version，防覆盖别人的修改）</span>
        </div>
      </el-card>

      <!-- 按批号追溯：食材出问题时，最想知道的是同一批还进了哪儿 -->
      <el-card v-if="detail.batchNo" shadow="never" class="section">
        <div class="section-title">
          按批号追溯
          <span class="sub">同批号的记录一次看全</span>
          <el-button class="trace-btn" type="primary" plain size="small"
                     :loading="tracing" @click="doTrace">
            追溯 {{ detail.batchNo }}
          </el-button>
        </div>

        <div v-if="traced" class="trace-body">
          <div class="trace-head">同批号共 {{ traced.length }} 条（含本行），点行可跳转</div>
          <el-table :data="traced" size="small" style="cursor: pointer"
                    empty-text="没有同批号的记录" @row-click="openOther">
            <el-table-column prop="id" label="#" width="58" />
            <el-table-column prop="purchaseDate" label="进货日期" width="104" />
            <el-table-column prop="ingredientName" label="名称" min-width="110" show-overflow-tooltip />
            <el-table-column prop="spec" label="规格" width="90" show-overflow-tooltip />
            <el-table-column prop="batchNo" label="批号" min-width="110" show-overflow-tooltip />
            <el-table-column prop="supplierName" label="供货者" min-width="120" show-overflow-tooltip />
            <el-table-column label="级别" width="96">
              <template #default="{ row }">
                <GradeTag :level="row.qualityLevel" />
              </template>
            </el-table-column>
            <el-table-column label="状态" width="96">
              <template #default="{ row }">
                {{ statusText(row.status, row.statusLabel) }}
              </template>
            </el-table-column>
            <el-table-column label="到期" width="106">
              <template #default="{ row }">
                {{ expiryText(row.expiryDaysLeft) }}
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-card>
    </template>

    <el-empty v-else-if="!loading" description="这条台账不存在，或者已经被删掉了" />
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import GradeTag from '../../shared/components/GradeTag.vue'
import {
  getLedger,
  postLedger,
  returnLedger,
  traceBatch,
  voidLedger
} from '../../shared/api/ledger'

/*
 * 台账详情与审核动作。
 *
 * 三件事必须在这一页说清楚：
 *   1 为什么是这个级别 —— 展示判定结论、messages 与 warnings，不只给个颜色
 *   2 每个值是从哪来的 —— 有 basis 就在值下面显示原文片段，让人能核对 AI 有没有读错
 *   3 这一行现在能做什么 —— 按钮按状态机和合规级别禁用，而不是点下去再挨一个 409
 *
 * 所有写操作都带 detail.version：两个人同时开着一行时，后点的人会收到 409，
 * 这里刷新成最新内容再让他决定，绝不静默覆盖。
 */
const route = useRoute()
const router = useRouter()

const STATUS_TEXT = {
  PENDING: '待入账',
  POSTED: '已入账',
  RETURNED: '退回补录',
  VOID: '已作废'
}

/** 模板 key 与后端固定列的对应关系；不在这里的 key 一律去 extra 里取 */
const FIXED_PROP = {
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
  receiver: 'receiver'
}

const loading = ref(false)
const acting = ref('')
const tracing = ref(false)
const detail = ref(null)
const traced = ref(null)

const fields = computed(() => detail.value?.fields || [])
const messages = computed(() => detail.value?.quality?.messages || [])
const warnings = computed(() => detail.value?.warnings || [])
const isVoid = computed(() => detail.value?.status === 'VOID')

const alertType = computed(() => {
  const level = detail.value?.qualityLevel
  if (level === 'RED') return 'error'
  if (level === 'YELLOW') return 'warning'
  return 'success'
})

const alertTitle = computed(() => {
  const level = detail.value?.qualityLevel
  if (level === 'RED') return '不合规 · 缺法定项目，不能入账'
  if (level === 'YELLOW') return '待补证 · 可以入账，但要补齐资料'
  if (level === 'GREEN') return '记录完整 · 法定项目齐全'
  return detail.value?.qualityLabel || '未判定'
})

const statusTagType = computed(() => {
  const status = detail.value?.status
  if (status === 'POSTED') return 'success'
  if (status === 'RETURNED') return 'warning'
  if (status === 'VOID') return 'info'
  return 'primary'
})

/** 入账被挡住的理由；为空表示可以点 */
const postBlocked = computed(() => {
  const d = detail.value
  if (!d) return ''
  if (d.status === 'POSTED') return '已入账，不能重复入账'
  if (d.status === 'RETURNED') return '等采购员补录、回到「待入账」后才能入账'
  if (d.qualityLevel === 'RED') return '缺法定项目，不能入账'
  return ''
})

const returnBlocked = computed(() => {
  const d = detail.value
  if (!d) return ''
  if (d.status !== 'PENDING') return '只有「待入账」可以退回补录'
  return ''
})

/** 状态相关的说明文字：解释为什么某些动作不可用 */
const stateLines = computed(() => {
  const d = detail.value
  if (!d) return []
  const lines = []
  if (d.status === 'POSTED') {
    lines.push(`已于 ${formatTime(d.postedAt)} 由 ${d.postedBy || '—'} 入账，之后只能作废，不能改回去。`)
  }
  if (d.status === 'VOID') {
    lines.push(d.voidReason ? `已作废，原因：${d.voidReason}` : '已作废。')
  }
  // 待补证只是「缺选填」，不是「不能入账」。
  // 不写清楚，管理员会以为得等采购员补齐，采购员也会以为要补完才能过。
  if (d.qualityLevel === 'YELLOW' && d.status === 'PENDING') {
    lines.push('待补证：只缺选填信息，可以直接入账；也可以退回让采购员补。')
  }
  if (d.status === 'RETURNED') {
    lines.push('已退回，等采购员补录后回到「待入账」；补不出来的话，可以直接作废。')
  }
  if (d.returnCount > 0) {
    lines.push(
      `已退回 ${d.returnCount} 次` + (d.returnNote ? `，最近一次要求：${d.returnNote}` : '')
    )
  }
  return lines
})

watch(
  () => route.params.id,
  (id) => {
    if (id) load(id)
  },
  { immediate: true }
)

async function load(id) {
  loading.value = true
  try {
    detail.value = await getLedger(id)
    traced.value = null
  } catch (e) {
    // 404 的提示由 http 层统一弹，这里只负责不留半截页面
    detail.value = null
  } finally {
    loading.value = false
  }
}

function back() {
  router.push('/ledger')
}

/* ---------------------------------------------------------------- 字段取值 */

function rawValue(field) {
  const prop = FIXED_PROP[field.key]
  if (prop) return detail.value?.[prop]
  return detail.value?.extra?.[field.key]
}

function isBlank(value) {
  return value === null || value === undefined || value === ''
}

function isMissing(field) {
  return !!field.required && isBlank(rawValue(field))
}

function displayValue(field) {
  const value = rawValue(field)
  if (isBlank(value)) return field.required ? '未填写' : '—'
  if (field.type === 'boolean') return value === true || value === 'true' ? '是' : '否'
  if (field.type === 'number') {
    const n = Number(value)
    if (!Number.isFinite(n)) return String(value)
    // 金额和单价带货币符号，免得把「28.5」看成数量
    if (field.key === 'amount' || field.key === 'unit_price') return `¥ ${n.toFixed(2)}`
    return String(n)
  }
  return String(value)
}

function basisOf(key) {
  const basis = detail.value?.basis
  if (!basis) return ''
  return basis[key] || ''
}

/* ---------------------------------------------------------------- 状态流转 */

async function doPost() {
  if (postBlocked.value) {
    ElMessage.warning(postBlocked.value)
    return
  }
  acting.value = 'post'
  try {
    detail.value = await postLedger(detail.value.id, detail.value.version)
    ElMessage.success('已入账')
  } catch (e) {
    await handleActionError(e)
  } finally {
    acting.value = ''
  }
}

async function doReturn() {
  if (returnBlocked.value) {
    ElMessage.warning(returnBlocked.value)
    return
  }
  let note = ''
  try {
    const res = await ElMessageBox.prompt(
      '退回后采购员可以继续补录。写清缺哪几项，他才知道要补什么。',
      '退回补录',
      {
        confirmButtonText: '确定退回',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: '例：缺供货者地址和许可证号',
        inputValidator: (v) => (v && v.trim() ? true : '写一句缺什么，采购员才补得准')
      }
    )
    note = String(res.value || '').trim()
    if (!note) return
  } catch (e) {
    return // 用户取消，不是错误
  }

  acting.value = 'return'
  try {
    detail.value = await returnLedger(detail.value.id, detail.value.version, note)
    ElMessage.success('已退回补录')
  } catch (e) {
    await handleActionError(e)
  } finally {
    acting.value = ''
  }
}

async function doVoid() {
  if (isVoid.value) return
  let reason = ''
  try {
    const res = await ElMessageBox.prompt(
      '台账不允许删除，只能作废并留痕。原因会一直挂在这条记录上，请写清楚。',
      '作废',
      {
        confirmButtonText: '确定作废',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: '例：重复录入，已由 #123 覆盖',
        inputValidator: (v) => (v && v.trim() ? true : '作废必须写原因——台账不允许无声消失')
      }
    )
    reason = String(res.value || '').trim()
  } catch (e) {
    return // 用户取消，不是错误
  }

  acting.value = 'void'
  try {
    detail.value = await voidLedger(detail.value.id, detail.value.version, reason)
    ElMessage.success('已作废')
  } catch (e) {
    await handleActionError(e)
  } finally {
    acting.value = ''
  }
}

/**
 * 409 = 有人在我们看着这一页的时候改了同一行。
 * http 层已经弹过服务端的原话，这里再补一句「已经刷新」，
 * 否则用户会以为界面没反应。
 */
async function handleActionError(err) {
  if (err && err.code === 409) {
    ElMessage.closeAll()
    ElMessage.warning('这行台账刚被人改过，已刷新')
    await load(detail.value.id)
  }
}

/* ---------------------------------------------------------------- 追溯 */

async function doTrace() {
  tracing.value = true
  try {
    traced.value = (await traceBatch(detail.value.batchNo)) || []
  } finally {
    tracing.value = false
  }
}

function openOther(row) {
  if (!row || row.id === detail.value?.id) return
  router.push(`/ledger/${row.id}`)
}

/* ---------------------------------------------------------------- 展示小工具 */

/** 图片才预览，PDF 走链接——把 PDF 塞进 el-image 只会显示一个破图标 */
function isImage(url) {
  return /\.(jpg|jpeg|png|webp)$/i.test(url || '')
}

function statusText(status, label) {
  return STATUS_TEXT[status] || label || status || '—'
}

function expiryText(days) {
  if (days === null || days === undefined) return '—'
  return days < 0 ? `过期 ${-days} 天` : `剩 ${days} 天`
}

/** 后端给的是 2026-10-26T09:30:00 这种格式，表格里不需要秒和 T */
function formatTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}

function showSession() {
  ElMessageBox.alert(
    `这条记录由 AI 录入会话 #${detail.value.sourceSessionId} 生成。` +
      '原始对话在会话里，可以据此核对每个字段是不是从原话里读出来的。',
    '来源会话',
    { confirmButtonText: '知道了' }
  ).catch(() => {})
}
</script>

<style scoped>
.attach { display: flex; flex-wrap: wrap; gap: 10px; }
.attach-item { display: flex; flex-direction: column; align-items: center; gap: 4px; border: 1px solid var(--line); border-radius: 6px; padding: 6px; }
.thumb { width: 96px; height: 96px; border-radius: 4px; display: block; }
.thumb.pdf { display: flex; align-items: center; justify-content: center; background: #f4f6f4; font-size: 13px; color: var(--text-muted); text-decoration: none; }
.attach-name { max-width: 96px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; color: var(--text-muted); }
.attach-empty { font-size: 12px; }

.page { width: 100%; }

.head-card :deep(.el-card__body) { padding: 12px 16px; }
.head { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.head-left { display: flex; align-items: center; gap: 10px; }
.back { font-size: 13px; color: var(--text-muted); }
.id { font-size: 18px; font-weight: 700; color: var(--text-main); }
.rev {
  font-size: 12px; color: var(--brand); background: #f2fbf4;
  border: 1px solid #cfe4d0; border-radius: 9px; padding: 0 7px;
}
.head-right { margin-left: auto; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.meta { font-size: 12px; color: var(--text-muted); }
.meta b { color: var(--text-main); font-weight: 600; }
.source { font-size: 12px; color: var(--text-muted); }
.source.ai { color: var(--brand); }
.session { font-size: 12px; }

.section { margin-top: 12px; }
.section-title {
  display: flex; align-items: center; gap: 8px;
  font-size: 13px; font-weight: 700; color: var(--text-main); margin-bottom: 12px;
}
.section-title .sub { font-size: 12px; font-weight: 400; color: var(--text-muted); }
.trace-btn { margin-left: auto; }

.msgs { font-size: 12px; line-height: 1.7; margin-top: 2px; }
.warnings { margin-top: 10px; display: flex; gap: 6px; flex-wrap: wrap; }

.grid {
  display: grid; gap: 14px 18px;
  grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
}
.cell { min-width: 0; }
.label { font-size: 12px; color: var(--text-muted); margin-bottom: 3px; }
.req { color: var(--red); }
.legal {
  margin-left: 5px; font-size: 10px; color: #8a94a6;
  border: 1px solid var(--line); border-radius: 3px; padding: 0 3px;
}
.value { font-size: 14px; color: var(--text-main); word-break: break-all; }
.value.blank { color: #c0c4cc; }
.value.missing { color: var(--red); }
.basis { margin-top: 3px; font-size: 12px; color: var(--text-muted); line-height: 1.5; }
.hint { margin-top: 3px; font-size: 12px; color: #b6bec9; }

.state-lines { margin-bottom: 12px; }
.line {
  font-size: 12px; color: #6b7683; line-height: 1.8;
  padding-left: 10px; border-left: 2px solid var(--line);
}
.btns { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.hold { display: inline-block; }
.version { margin-left: auto; font-size: 12px; color: var(--text-muted); }

.trace-body { margin-top: 4px; }
.trace-head { font-size: 12px; color: var(--text-muted); margin-bottom: 8px; }
</style>
