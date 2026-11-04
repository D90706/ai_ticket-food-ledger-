<template>
  <div class="page">
    <el-card shadow="never" class="bar-card">
      <div class="bar">
        <el-input v-model="keyword" placeholder="名称 / 联系人 / 电话" clearable
                  style="width: 212px" @keyup.enter="load" />
        <el-select v-model="status" placeholder="全部状态" clearable style="width: 116px" @change="load">
          <el-option label="正常" value="ACTIVE" />
          <el-option label="停用" value="DISABLED" />
        </el-select>
        <el-button type="primary" :loading="loading" @click="load">搜索</el-button>
        <el-button :disabled="loading" @click="reset">重置</el-button>

        <div class="count">
          共 {{ rows.length }} 家
          <span v-if="warnCount">· 证照预警 <b class="danger">{{ warnCount }}</b> 家</span>
        </div>

        <el-button type="primary" plain @click="openCreate">新增供应商</el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table v-loading="loading" :data="rows" size="small" :row-class-name="rowClass"
                empty-text="还没有供应商，先新增一家">
        <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="name">{{ row.name }}</span>
            <el-tag v-if="row.licenseWarning" type="danger" size="small" effect="plain"
                    class="warn-tag">证照临期</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="contact" label="联系人" width="94">
          <template #default="{ row }">{{ row.contact || '—' }}</template>
        </el-table-column>
        <el-table-column prop="phone" label="电话" width="124">
          <template #default="{ row }">{{ row.phone || '—' }}</template>
        </el-table-column>
        <el-table-column prop="licenseNo" label="许可证号" min-width="138" show-overflow-tooltip>
          <template #default="{ row }">{{ row.licenseNo || '—' }}</template>
        </el-table-column>
        <el-table-column label="许可证到期" width="130">
          <template #default="{ row }">
            <div class="date">{{ row.licenseExpiry || '—' }}</div>
            <div :class="daysClass(row.licenseDaysLeft)">{{ daysText(row.licenseDaysLeft) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="营业执照到期" width="130">
          <template #default="{ row }">
            <div class="date">{{ row.businessExpiry || '—' }}</div>
            <div :class="daysClass(row.businessDaysLeft)">{{ daysText(row.businessDaysLeft) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
              {{ row.status === 'ACTIVE' ? '正常' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="72" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialog" :title="form.id ? '编辑供应商' : '新增供应商'"
               width="540px" :close-on-click-modal="false">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="106px" size="small">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="80" placeholder="与台账上的供货者名称一致" />
        </el-form-item>
        <el-form-item label="地址" prop="address">
          <el-input v-model="form.address" maxlength="200" />
        </el-form-item>
        <el-form-item label="联系人" prop="contact">
          <el-input v-model="form.contact" maxlength="40" />
        </el-form-item>
        <el-form-item label="电话" prop="phone">
          <el-input v-model="form.phone" maxlength="40" />
        </el-form-item>
        <el-form-item label="许可证号" prop="licenseNo">
          <el-input v-model="form.licenseNo" maxlength="80" />
        </el-form-item>
        <el-form-item label="许可证到期" prop="licenseExpiry">
          <el-date-picker v-model="form.licenseExpiry" type="date" value-format="YYYY-MM-DD"
                          placeholder="选到期日" clearable style="width: 100%" />
        </el-form-item>
        <el-form-item label="营业执照号" prop="businessLicenseNo">
          <el-input v-model="form.businessLicenseNo" maxlength="80" />
        </el-form-item>
        <el-form-item label="营业执照到期" prop="businessExpiry">
          <el-date-picker v-model="form.businessExpiry" type="date" value-format="YYYY-MM-DD"
                          placeholder="选到期日" clearable style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createSupplier, listSuppliers, updateSupplier } from '../../shared/api/ledger'

/*
 * 供应商管理。
 *
 * 「证照还剩几天」和 licenseWarning 都由后端算好（SupplierService），
 * 这里只负责显示：一旦前端自己拿今天去减日期，规则就会在各个页面漂移，
 * 而且浏览器时区和服务器时区差一天就会吵起来。
 *
 * 新增与编辑共用一个对话框：字段完全一样，分成两个只会让校验规则写两遍。
 */
const rows = ref([])
const keyword = ref('')
const status = ref('')
const loading = ref(false)

const dialog = ref(false)
const saving = ref(false)
const formRef = ref(null)

const form = reactive({
  id: null,
  name: '',
  address: '',
  contact: '',
  phone: '',
  licenseNo: '',
  licenseExpiry: '',
  businessLicenseNo: '',
  businessExpiry: '',
  remark: ''
})

const rules = {
  name: [{ required: true, message: '供应商名称不能为空', trigger: 'blur' }]
}

const warnCount = computed(() => rows.value.filter((r) => r.licenseWarning).length)

onMounted(load)

async function load() {
  loading.value = true
  try {
    rows.value = (await listSuppliers({
      keyword: keyword.value.trim() || undefined,
      status: status.value || undefined
    })) || []
  } finally {
    loading.value = false
  }
}

function reset() {
  keyword.value = ''
  status.value = ''
  load()
}

/** 预警行整行铺一层浅红，扫一眼就知道哪几家要先催证 */
function rowClass({ row }) {
  return row.licenseWarning ? 'row-warn' : ''
}

/**
 * 30 天是后端 app.ledger.license-warn-days 的默认值，
 * 颜色阈值只是显示习惯，真正的 licenseWarning 用后端给的布尔值。
 */
function daysText(days) {
  if (days === null || days === undefined) return '未登记'
  if (days < 0) return `已过期 ${-days} 天`
  return `剩 ${days} 天`
}

function daysClass(days) {
  if (days === null || days === undefined) return 'none'
  if (days < 0) return 'danger'
  if (days <= 30) return 'warn'
  return 'ok'
}

function openCreate() {
  Object.assign(form, {
    id: null,
    name: '',
    address: '',
    contact: '',
    phone: '',
    licenseNo: '',
    licenseExpiry: '',
    businessLicenseNo: '',
    businessExpiry: '',
    remark: ''
  })
  dialog.value = true
  nextTick(() => formRef.value?.clearValidate())
}

function openEdit(row) {
  Object.assign(form, {
    id: row.id,
    name: row.name || '',
    address: row.address || '',
    contact: row.contact || '',
    phone: row.phone || '',
    licenseNo: row.licenseNo || '',
    licenseExpiry: row.licenseExpiry || '',
    businessLicenseNo: row.businessLicenseNo || '',
    businessExpiry: row.businessExpiry || '',
    remark: row.remark || ''
  })
  dialog.value = true
  nextTick(() => formRef.value?.clearValidate())
}

async function save() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  // 空字符串一律换成 null：后端把空串当「有值」存下来，"没填"就不再是没填
  const payload = {
    name: form.name.trim(),
    address: blankToNull(form.address),
    contact: blankToNull(form.contact),
    phone: blankToNull(form.phone),
    licenseNo: blankToNull(form.licenseNo),
    licenseExpiry: form.licenseExpiry || null,
    businessLicenseNo: blankToNull(form.businessLicenseNo),
    businessExpiry: form.businessExpiry || null,
    remark: blankToNull(form.remark)
  }

  saving.value = true
  try {
    if (form.id) {
      await updateSupplier(form.id, payload)
      ElMessage.success('已保存')
    } else {
      await createSupplier(payload)
      ElMessage.success('已新增')
    }
    dialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

function blankToNull(value) {
  const text = (value || '').trim()
  return text ? text : null
}
</script>

<style scoped>
.page { width: 100%; }

.bar-card :deep(.el-card__body) { padding: 10px 12px; }
.bar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.count { margin-left: auto; font-size: 12px; color: var(--text-muted); }
.count b { font-size: 13px; }

.table-card { margin-top: 12px; }
.table-card :deep(.el-card__body) { padding: 4px 0 0; }

.name { font-weight: 600; color: var(--text-main); }
.warn-tag { margin-left: 6px; }

.date { font-size: 12px; color: var(--text-main); line-height: 1.5; }
.none { font-size: 12px; color: #c0c4cc; }
.ok { font-size: 12px; color: var(--text-muted); }
.warn { font-size: 12px; color: var(--yellow); font-weight: 600; }
.danger { font-size: 12px; color: var(--red); font-weight: 600; }

:deep(.el-table__body .row-warn > td.el-table__cell) { background: #fdf3f3; }
</style>
