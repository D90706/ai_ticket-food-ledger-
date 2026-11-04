<template>
  <div class="panel">
    <div class="head">
      <span class="title">手填录入</span>
      <span class="sub">不想跟 AI 说话时，直接填——两条路判定规则完全一样</span>
      <el-button link size="small" class="reset" @click="reset">清空</el-button>
    </div>

    <div class="body">
      <el-alert v-if="!fields.length" type="info" :closable="false"
                title="正在加载模板字段…" />

      <template v-else>
        <LedgerForm v-model="values" :fields="fields" />

        <div class="actions">
          <el-button type="primary" :loading="saving" @click="submit">
            提交台账记录
          </el-button>
          <span class="tip">
            提交后会立刻做合规判定：缺法定项目就是「不合规」，不能入账
          </span>
        </div>

        <!-- 凭证照片：法定要求是「保存相关凭证」，照片比编号更接近凭证本身 -->
        <div class="cert">
          <div class="group-title">
            索证索票凭证照片
            <span class="hint">拍了照直接传，凭证那栏就不用填编号</span>
          </div>
          <el-upload :show-file-list="false" :http-request="doUpload"
                     accept=".jpg,.jpeg,.png,.webp,.pdf">
            <el-button size="small" :loading="uploading">上传凭证照片</el-button>
          </el-upload>
          <ul v-if="attachments.length" class="files">
            <li v-for="(f, i) in attachments" :key="f.url">
              <a :href="f.url" target="_blank" rel="noopener">{{ f.name }}</a>
              <el-button link size="small" type="danger" @click="attachments.splice(i, 1)">
                删除
              </el-button>
            </li>
          </ul>
        </div>
      </template>
    </div>

    <!-- 结果卡：只告诉记录号、级别、缺什么，不铺一堆字段 -->
    <div v-if="result" class="result">
      <div class="result-title">已记入台账</div>
      <div class="result-body">
        记录号 <b>#{{ result.id }}</b>　级别 <GradeTag :level="result.qualityLevel" />
      </div>
      <ul v-if="result.messages && result.messages.length" class="msgs">
        <li v-for="m in result.messages" :key="m">{{ m }}</li>
      </ul>
      <div class="result-actions">
        <el-button size="small" @click="reset">再填一条</el-button>
        <!--
          注意：这里必须发一个专门的 go-mine 事件。
          原来写的是 $emit('recorded')，而父组件收到 recorded 只刷新列表、不切页签，
          于是按钮点下去毫无反应——「按钮在这儿，行为在别处」，是很容易漏的一类连线错误。
        -->
        <el-button size="small" type="primary" plain @click="emit('go-mine')">
          去「我的记录」
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import GradeTag from '../../shared/components/GradeTag.vue'
import LedgerForm from '../../shared/components/LedgerForm.vue'
import { createLedgerFromValues } from '../../shared/api/ledger'
import { getDefaultTemplate } from '../../shared/api/template'
import { uploadFile } from '../../shared/api/upload'
import { loadDraft, saveDraft, clearDraft } from '../../shared/draft'

/**
 * 手填录入。
 *
 * 它存在的理由很朴素：**AI 是加速器，不是唯一入口。**
 * 模型挂了、网断了、或者采购员就是想自己填，都得能记账。
 * 一个"只有 AI 能录入"的台账系统，在食堂里是不敢用的。
 *
 * 判定结果与 AI 录入完全一致，因为后端走同一个映射器和同一段判定。
 */
const emit = defineEmits(['recorded', 'go-mine'])

// 草稿按用户隔离：共享终端上不把上一个人的录入留给下一个人
const props = defineProps({ staff: { type: Object, default: null } })
const draftName = 'manual'

const fields = ref([])
const values = ref({})
const saving = ref(false)
const uploading = ref(false)
const attachments = ref([])
const result = ref(null)

onMounted(async () => {
  const template = await getDefaultTemplate()
  fields.value = template.fields || []

  // 先看有没有没提交完的草稿。有就接着填，别让人重打一遍。
  const saved = loadDraft(draftName, user())
  reset()
  if (saved && saved.values) {
    values.value = { ...values.value, ...saved.values }
    attachments.value = saved.attachments || []
    ElMessage.info('已恢复上次未提交的录入内容')
  }
})

function user() {
  return props.staff?.displayName || ''
}

function reset() {
  const blank = {}
  fields.value.forEach((f) => {
    blank[f.key] = null
  })
  // 进货日期默认今天：这是最常填错也最容易省掉的一项
  if (fields.value.some((f) => f.key === 'purchase_date')) {
    blank.purchase_date = new Date().toISOString().slice(0, 10)
  }
  values.value = blank
  attachments.value = []
  result.value = null
  clearDraft(draftName, user())
}

// 任何改动都落草稿：切页签、刷新、跳去管理端再回来都还在
let draftTimer = null
watch(
  () => [values.value, attachments.value],
  () => {
    clearTimeout(draftTimer)
    draftTimer = setTimeout(() => {
      saveDraft(draftName, user(), { values: values.value, attachments: attachments.value })
    }, 300)
  },
  { deep: true }
)

async function doUpload(options) {
  uploading.value = true
  try {
    const file = await uploadFile(options.file)
    attachments.value.push(file)
    ElMessage.success('凭证已上传')
  } finally {
    uploading.value = false
  }
}

async function submit() {
  if (!values.value.ingredient_name || !String(values.value.ingredient_name).trim()) {
    ElMessage.warning('名称是空的——台账没有名目就不成记录')
    return
  }
  saving.value = true
  try {
    // 附件一起提交：后端映射器认得 attachments 这个键，
    // 合规判定会把「有凭证照片」视为凭证已提供
    const detail = await createLedgerFromValues({
      ...values.value,
      attachments: attachments.value
    })
    result.value = {
      id: detail.id,
      qualityLevel: detail.qualityLevel,
      messages: [
        ...(detail.quality?.messages || []),
        ...(detail.warnings || [])
      ]
    }
    clearDraft(draftName, user())   // 已入台账，草稿没用了
    ElMessage.success('已提交')
    emit('recorded')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.cert { margin-top: 14px; }
.cert .files { margin: 8px 0 0; padding-left: 18px; font-size: 12px; }
.cert .files a { color: var(--brand); margin-right: 8px; }
.panel {
  width: 100%;
  max-width: 760px;
  height: 100%;
  min-height: 0;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 2px 14px rgba(21, 40, 66, 0.07);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.head {
  height: 50px; flex: 0 0 50px; border-bottom: 1px solid var(--line);
  display: flex; align-items: center; gap: 10px; padding: 0 18px;
}
.title { font-weight: 700; color: var(--text-main); }
.sub { font-size: 12px; color: var(--text-muted); }
.reset { margin-left: auto; }
.body { flex: 1; min-height: 0; overflow-y: auto; padding: 16px 18px; }
.actions { margin-top: 8px; display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.tip { font-size: 12px; color: var(--text-muted); }
.result {
  margin: 0 18px 16px; padding: 14px 16px; border: 1px solid #d6ecd9;
  background: #f2fbf4; border-radius: 8px;
}
.result-title { font-size: 15px; font-weight: 700; color: #2e7d4f; margin-bottom: 6px; }
.result-body { font-size: 14px; color: #35513d; }
.msgs { margin: 8px 0 10px; padding-left: 18px; font-size: 13px; color: #a86b1a; }
.result-actions { display: flex; gap: 10px; }
</style>
