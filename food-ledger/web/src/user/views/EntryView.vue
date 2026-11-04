<template>
  <div class="panel">
    <div class="head">
      <span class="dot"></span>
      <span class="title">AI 记账助手</span>
      <el-button v-if="sessionId" link size="small" class="restart" @click="restart">
        重新开始
      </el-button>
    </div>

    <div ref="scrollBox" class="messages">
      <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
        <div class="bubble">{{ m.content }}</div>
      </div>

      <div v-if="loading" class="msg assistant">
        <div class="bubble thinking">正在整理这笔采购…</div>
      </div>

      <!-- 待确认：给「一句话」，不给字段表。摘要可编辑，改完再提交。 -->
      <div v-if="confirmSummary !== null" class="confirm">
        <div class="confirm-title">请确认这条记录的内容</div>
        <el-input v-model="confirmSummary" type="textarea" :rows="3" resize="none" />
        <div v-if="missing.length" class="missing">
          还缺：
          <el-tag v-for="m in missing" :key="m" size="small" type="warning" effect="plain">
            {{ m }}
          </el-tag>
        </div>
        <div class="confirm-actions">
          <el-button type="primary" :loading="submitting" @click="submitConfirm">
            确认并生成台账记录
          </el-button>
          <span class="tip">不准确可以直接改上面的文字，也可以在下面补充一句</span>
        </div>
      </div>

      <!-- 已生成：只告诉采购员记录号与状态。入账是管理员的动作。 -->
      <div v-if="result" class="result">
        <div class="result-title">已记入台账</div>
        <div class="result-body">
          记录号 <b>#{{ result.recordId }}</b>　级别 <GradeTag :level="result.level" />
        </div>
        <div class="result-note">
          当前状态「待入账」，需要管理员审核后才算正式入账。
        </div>
        <div class="result-actions">
          <el-button size="small" @click="restart">再记一笔</el-button>
          <el-button size="small" type="primary" plain @click="emit('go-mine')">
            去「我的记录」
          </el-button>
        </div>
      </div>
    </div>

    <div v-if="!result" class="input">
      <el-input
        v-model="draft"
        type="textarea"
        :rows="2"
        resize="none"
        :disabled="loading"
        placeholder="例：今天进了20斤冷鲜猪肉，单价28块5，城南肉联厂送的，批号P20261026-3"
        @keydown.enter.exact.prevent="send"
      />
      <div class="side">
        <!--
          凭证照片入口。放在这里而不是只放在「补录」页：
          对话问到「索证索票凭证」时，采购员手上就是一张照片，
          让他退出去补录再回来，等于把一件事拆成两件事。
        -->
        <el-upload :show-file-list="false" :http-request="uploadCert" accept=".jpg,.jpeg,.png,.webp,.pdf">
          <el-button size="small" :loading="uploading">传凭证</el-button>
        </el-upload>
        <el-button type="primary" class="send" :loading="loading" :disabled="!draft.trim()"
                   @click="send">
          发送
        </el-button>
      </div>
    </div>
    <div v-if="!result && files.length" class="files">
      已上传凭证：
      <a v-for="f in files" :key="f.url" :href="f.url" target="_blank" rel="noopener">{{ f.name }}</a>
    </div>
    <div v-if="!result" class="hint">Enter 发送 / Shift + Enter 换行</div>
  </div>
</template>

<script setup>
import { nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import GradeTag from '../../shared/components/GradeTag.vue'
import {
  attachToEntry,
  confirmEntry,
  createEntrySession,
  sendEntryMessage
} from '../../shared/api/entry'
import { uploadFile } from '../../shared/api/upload'
import { loadDraft, saveDraft, clearDraft } from '../../shared/draft'

const props = defineProps({ staff: { type: Object, default: null } })
const emit = defineEmits(['recorded', 'go-mine'])

const draftName = 'entry'
const user = () => props.staff?.displayName || ''

const sessionId = ref(null)
const messages = ref([])
const draft = ref('')
const loading = ref(false)
const submitting = ref(false)
const uploading = ref(false)
const files = ref([])
const scrollBox = ref(null)

const confirmSummary = ref(null)
const missing = ref([])
const result = ref(null)

onMounted(async () => {
  // 有未完成的对话就接着聊：切页签或刷新后不该从问候语重新开始
  const saved = loadDraft(draftName, user())
  if (saved && saved.sessionId) {
    sessionId.value = saved.sessionId
    messages.value = saved.messages || []
    draft.value = saved.draft || ''
    result.value = saved.result || null
    confirmSummary.value = saved.confirmSummary || null
    missing.value = saved.missing || []
    files.value = saved.files || []
    if (!messages.value.length) await start()
    return
  }
  await start()
})

// 对话、草稿文本、结果卡都存下来
let draftTimer = null
watch(
  () => [sessionId.value, messages.value, draft.value, result.value, confirmSummary.value, missing.value, files.value],
  () => {
    clearTimeout(draftTimer)
    draftTimer = setTimeout(() => {
      saveDraft(draftName, user(), {
        sessionId: sessionId.value,
        messages: messages.value,
        draft: draft.value,
        result: result.value,
        confirmSummary: confirmSummary.value,
        missing: missing.value,
        files: files.value
      })
    }, 300)
  },
  { deep: true }
)

async function start() {
  const data = await createEntrySession()
  sessionId.value = data.sessionId
  messages.value = [
    {
      role: 'assistant',
      content:
        '您好，请用一句话说说这笔采购：什么货、多少、谁供的、什么时候进的。' +
        '说全了我直接记账，说不全我再问您。'
    }
  ]
}

async function send() {
  const text = draft.value.trim()
  if (!text || loading.value || result.value) return

  messages.value.push({ role: 'user', content: text })
  draft.value = ''
  // 待确认时又说话：当作补充，确认卡收起
  confirmSummary.value = null
  missing.value = []
  loading.value = true
  await scrollToBottom()

  try {
    const data = await sendEntryMessage(sessionId.value, text)
    if (data.replyType === 'ASK') {
      messages.value.push({ role: 'assistant', content: data.question })
    } else if (data.replyType === 'CONFIRM') {
      messages.value.push({
        role: 'assistant',
        content: '信息还不太完整。我先按理解整理了一版，请确认后提交，或者直接补充缺的内容。'
      })
      confirmSummary.value = data.summary || ''
      missing.value = data.missing || []
    } else {
      onRecorded(data)
    }
    await scrollToBottom()
  } finally {
    loading.value = false
  }
}

async function submitConfirm() {
  submitting.value = true
  try {
    const data = await confirmEntry(sessionId.value, confirmSummary.value)
    confirmSummary.value = null
    missing.value = []
    onRecorded(data)
    await scrollToBottom()
  } finally {
    submitting.value = false
  }
}

/**
 * 上传凭证照片：先传到附件口拿 URL，再挂到这次录入上。
 * 拆两步的好处是白名单、大小限制、文件名生成只有一份实现。
 */
async function uploadCert(options) {
  uploading.value = true
  try {
    const attachment = await uploadFile(options.file)
    files.value.push(attachment)
    const data = await attachToEntry(sessionId.value, attachment)
    messages.value.push({ role: 'user', content: '（上传了凭证照片：' + attachment.name + '）' })
    if (data.replyType === 'ASK') {
      messages.value.push({ role: 'assistant', content: data.question })
    } else if (data.replyType === 'CONFIRM') {
      confirmSummary.value = data.summary || ''
      missing.value = data.missing || []
    } else if (data.replyType === 'RECORD') {
      onRecorded(data)
    }
    ElMessage.success('凭证已上传')
    await scrollToBottom()
  } finally {
    uploading.value = false
  }
}

function onRecorded(data) {
  result.value = { recordId: data.recordId, level: data.level }
  messages.value.push({
    role: 'assistant',
    content: `已记入台账，记录号 #${data.recordId}。管理员审核后即可入账。`
  })
  emit('recorded', data)
}

function restart() {
  clearDraft(draftName, user())   // 重新开始 = 主动丢弃这轮草稿
  files.value = []
  sessionId.value = null
  messages.value = []
  draft.value = ''
  confirmSummary.value = null
  missing.value = []
  result.value = null
  start()
}

async function scrollToBottom() {
  await nextTick()
  if (scrollBox.value) scrollBox.value.scrollTop = scrollBox.value.scrollHeight
}
</script>

<style scoped>
.panel {
  width: 100%;
  max-width: 720px;
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
  display: flex; align-items: center; gap: 8px; padding: 0 18px;
}
.dot { width: 8px; height: 8px; border-radius: 50%; background: var(--green); }
.title { font-weight: 600; color: var(--text-main); font-size: 14px; }
.restart { margin-left: auto; }
.messages { flex: 1; min-height: 0; overflow-y: auto; padding: 18px 18px 8px; background: #fafbfa; }
.msg { display: flex; margin-bottom: 12px; }
.msg.user { justify-content: flex-end; }
.bubble {
  max-width: 78%; padding: 10px 14px; border-radius: 10px; background: #fff;
  border: 1px solid var(--line); font-size: 14px; line-height: 1.65;
  white-space: pre-wrap; word-break: break-word;
}
.msg.user .bubble { background: var(--brand); border-color: var(--brand); color: #fff; }
.thinking { color: var(--text-muted); }
.confirm {
  margin: 4px 0 14px; padding: 14px 16px; border: 1px solid #f3e2c7;
  background: #fffaf1; border-radius: 8px;
}
.confirm-title { font-size: 14px; font-weight: 700; color: #a86b1a; margin-bottom: 10px; }
.missing { margin-top: 10px; font-size: 13px; color: #a86b1a; display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
.confirm-actions { margin-top: 12px; display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.tip { font-size: 12px; color: #b08a52; }
.result {
  margin: 4px 0 14px; padding: 16px; border: 1px solid #d6ecd9;
  background: #f2fbf4; border-radius: 8px;
}
.result-title { font-size: 15px; font-weight: 700; color: #2e7d4f; margin-bottom: 6px; }
.result-body { font-size: 14px; color: #35513d; margin-bottom: 6px; }
.result-note { font-size: 12px; color: #7a9c86; margin-bottom: 10px; }
.input { display: flex; gap: 10px; padding: 12px 16px 6px; border-top: 1px solid var(--line); align-items: flex-end; }
.send { height: 54px; padding: 0 22px; }
.side {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-self: stretch;
  justify-content: flex-end;
}
.side .send { margin: 0; }
.files {
  padding: 0 18px 6px;
  font-size: 12px;
  color: var(--text-muted);
}
.files a { margin-right: 10px; color: var(--brand); }
.hint { padding: 0 16px 10px; font-size: 12px; color: #c0c4cc; }
</style>
