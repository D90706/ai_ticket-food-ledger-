<template>
  <div class="panel">
    <!-- 状态一：还没开始，先选咨询类型 -->
    <div v-if="!sessionId" class="entry">
      <h1>您好，请选择要咨询的问题类型</h1>
      <p class="sub">
        我们会先了解情况，能直接处理就直接处理；需要人工决定的，会整理成工单转交。
      </p>

      <div class="types">
        <button
          v-for="t in templates"
          :key="t.id"
          class="type"
          :class="{ active: selected === t.id }"
          @click="selected = t.id"
        >
          <span class="type-name">{{ t.name }}</span>
          <span class="type-check" v-if="selected === t.id">✓</span>
        </button>
      </div>

      <el-empty v-if="!templates.length" description="暂时没有可咨询的类型" :image-size="70" />

      <el-button
        type="primary"
        size="large"
        class="start"
        :disabled="!selected"
        :loading="starting"
        @click="start"
      >
        开始咨询
      </el-button>
    </div>

    <!-- 状态二：对话中 / 已出单 -->
    <div v-else class="chat">
      <div class="chat-head">
        <span class="dot"></span>
        <span class="who">{{ currentName }} · 智能客服</span>
        <el-button link size="small" class="restart" @click="restart">重新咨询</el-button>
      </div>

      <div class="messages" ref="scrollBox">
        <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
          <div class="bubble">{{ m.content }}</div>
        </div>
        <div v-if="loading" class="msg assistant">
          <div class="bubble thinking">正在整理信息…</div>
        </div>
      </div>

      <!-- 待确认：信息不全时，先让顾客看一眼再入队。
           这里给的是「一句话」，不是字段表——字段是给我们用的压缩表示。 -->
      <div v-if="confirmDraft !== null && !ticket" class="confirm">
        <div class="confirm-title">请确认我们的理解是否正确</div>
        <el-input
          v-model="confirmDraft"
          type="textarea"
          :rows="3"
          resize="none"
          class="confirm-text"
        />
        <div v-if="missingLabels.length" class="confirm-missing">
          还缺少：
          <el-tag v-for="m in missingLabels" :key="m" size="small" type="warning" effect="plain">
            {{ m }}
          </el-tag>
        </div>
        <div class="confirm-actions">
          <el-button type="primary" :loading="confirming" @click="submitConfirm">
            确认并提交给人工
          </el-button>
          <span class="confirm-tip">不准确可以直接改上面的文字，或在下方补充一句</span>
        </div>
      </div>
      <!-- 出单后：顾客只需要知道「已转人工」和工单号，不该看到内部分级 -->
      <div v-if="ticket && supplementing" class="done returned">
        <div class="done-title">人工客服需要您补充信息</div>
        <div class="done-body">
          工单号 <b>#{{ ticket.ticketId }}</b>。回答下面的问题后，我会重新整理并提交。
        </div>
      </div>

      <div v-if="ticket && !supplementing" class="done">
        <div class="done-title">已为您转交人工客服</div>
        <div class="done-body">
          工单号 <b>#{{ ticket.ticketId }}</b>，
          <template v-if="currentStatus">当前状态：<b>{{ statusText(currentStatus) }}</b></template>
          <template v-else>客服会尽快处理。</template>
        </div>
        <div v-if="currentStatus && !TERMINAL.includes(currentStatus)" class="done-wait">
          已转交人工，处理进度会自动通知您，不用一直等着。
        </div>
        <el-button size="small" @click="restart">再咨询一个问题</el-button>
      </div>

      <div v-if="!ticket || supplementing" class="input">
        <el-input
          v-model="draft"
          type="textarea"
          :rows="2"
          resize="none"
          :disabled="loading"
          placeholder="请描述您的问题，例如：订单号 2026…，收到的 M 码太大，想换 S"
          @keydown.enter.exact.prevent="send"
        />
        <el-button
          type="primary"
          class="send"
          :loading="loading"
          :disabled="!draft.trim()"
          @click="send"
        >
          发送
        </el-button>
      </div>
      <div v-if="!ticket || supplementing" class="hint">Enter 发送 / Shift + Enter 换行</div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import {
  checkStatus,
  confirmTicket,
  createSession,
  getHistory,
  listChatTemplates,
  sendMessage
} from '../../shared/api/chat'

const templates = ref([])
const selected = ref(null)
const starting = ref(false)

const sessionId = ref(null)
const messages = ref([])
const draft = ref('')
const loading = ref(false)
const ticket = ref(null)
const scrollBox = ref(null)
const currentName = ref('')

// 待确认状态：null 表示没有待确认的工单
const confirmDraft = ref(null)
const missingLabels = ref([])
const confirming = ref(false)

// 工单状态回执：出单后轮询，状态跨过节点时追加一条气泡
const currentStatus = ref(null)
let pollTimer = null
const TERMINAL = ['RESOLVED', 'CLOSED']
const STORAGE_KEY = 'ai-ticket.session'

onMounted(async () => {
  // 先看有没有上次没结束的会话：工单回执往往在几十秒后才来，
  // 顾客刷新一下页面就丢会话的话，回执永远看不到。
  const resumed = await resume()
  if (resumed) return

  templates.value = await listChatTemplates()
  // 只有一个类型时默认选中，少点一次
  if (templates.value.length === 1) selected.value = templates.value[0].id
})

onUnmounted(stopPolling)

async function start() {
  starting.value = true
  try {
    const picked = templates.value.find((x) => x.id === selected.value)
    currentName.value = picked ? picked.name : ''
    const data = await createSession(selected.value)
    sessionId.value = data.sessionId
    messages.value = [{ role: 'assistant', content: data.greeting }]
    ticket.value = null
    draft.value = ''
    persist()
  } finally {
    starting.value = false
  }
}

async function send() {
  const text = draft.value.trim()
  if (!text || loading.value || (ticket.value && !supplementing.value)) return

  messages.value.push({ role: 'user', content: text })
  draft.value = ''
  // 待确认时又说了话，视为补充信息：清掉确认卡，等后端重新抽取
  confirmDraft.value = null
  missingLabels.value = []
  loading.value = true
  await scrollToBottom()
  try {
    const data = await sendMessage(sessionId.value, text)
    if (data.replyType === 'ASK') {
      messages.value.push({ role: 'assistant', content: data.question })
    } else if (data.replyType === 'CONFIRM') {
      // 后端把提示语放在 messages 里，前端不复制文案
      ;(data.messages || []).forEach((m) =>
        messages.value.push({ role: 'assistant', content: m })
      )
      confirmDraft.value = data.summary || ''
      missingLabels.value = data.missing || []
    } else {
      messages.value.push({
        role: 'assistant',
        content: data.resubmitted
          ? '信息已补全，已重新提交给人工客服。'
          : '已经帮您整理好并转交人工客服了，请稍候。'
      })
      ticket.value = { ticketId: data.ticketId }
      // 重新提交后状态回到待处理，等下一轮轮询刷新
      if (data.resubmitted) currentStatus.value = 'PENDING'
      persist()
      startPolling()
    }
    await scrollToBottom()
  } finally {
    loading.value = false
  }
}

async function submitConfirm() {
  confirming.value = true
  try {
    const data = await confirmTicket(sessionId.value, confirmDraft.value)
    confirmDraft.value = null
    missingLabels.value = []
    messages.value.push({
      role: 'assistant',
      content: '已经帮您整理好并转交人工客服了，请稍候。'
    })
    ticket.value = { ticketId: data.ticketId }
    persist()
    startPolling()
    await scrollToBottom()
  } finally {
    confirming.value = false
  }
}

function restart() {
  stopPolling()
  clearPersisted()
  currentStatus.value = null
  sessionId.value = null
  confirmDraft.value = null
  missingLabels.value = []
  messages.value = []
  ticket.value = null
  draft.value = ''
  loading.value = false
}

// ---------------------------------------------------------------- 会话接续

function persist() {
  try {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ sessionId: sessionId.value, name: currentName.value })
    )
  } catch (e) {
    /* 隐私模式下 localStorage 可能不可用，忽略即可 */
  }
}

function clearPersisted() {
  try {
    localStorage.removeItem(STORAGE_KEY)
  } catch (e) {
    /* 同上 */
  }
}

/** 刷新后接上上次的会话：拿回历史消息与工单状态 */
async function resume() {
  let saved = null
  try {
    saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null')
  } catch (e) {
    return false
  }
  if (!saved || !saved.sessionId) return false

  try {
    const history = await getHistory(saved.sessionId)
    sessionId.value = saved.sessionId
    currentName.value = saved.name || ''
    messages.value = history.map((m) => ({ role: m.role, content: m.content }))
  } catch (e) {
    // 会话已经不存在（比如重建了库），就当作新会话
    clearPersisted()
    return false
  }

  const status = await safeStatus()
  if (status && status.ticketId) {
    ticket.value = { ticketId: status.ticketId }
    currentStatus.value = status.status
    // 首次进来也可能带回一条新回执（比如工单刚被打回）。
    // 这条回执在服务端已经"消费"掉了，不在这里追加就永远看不到了。
    if (status.changed && status.notice) {
      messages.value.push({ role: 'assistant', content: status.notice })
    }
    if (!TERMINAL.includes(status.status)) startPolling()
  }
  await scrollToBottom()
  return true
}

// ---------------------------------------------------------------- 状态回执

function startPolling() {
  stopPolling()
  pollTimer = setInterval(async () => {
    const status = await safeStatus()
    if (!status) return
    if (!ticket.value && status.ticketId) ticket.value = { ticketId: status.ticketId }
    currentStatus.value = status.status
    if (status.changed && status.notice) {
      messages.value.push({ role: 'assistant', content: status.notice })
      await scrollToBottom()
    }
    if (TERMINAL.includes(status.status)) stopPolling()
  }, 4000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function safeStatus() {
  try {
    return await checkStatus(sessionId.value)
  } catch (e) {
    return null
  }
}

/** 工单被打回：顾客可以继续说话 */
const supplementing = computed(() => currentStatus.value === 'RETURNED')

const STATUS_TEXT = {
  PENDING: '待处理',
  INFO_INCOMPLETE: '信息不全',
  ASSIGNED: '人工已受理',
  PROCESSING: '处理中',
  RESOLVED: '已处理完成',
  CLOSED: '已关闭'
}

function statusText(s) {
  return STATUS_TEXT[s] || s
}

async function scrollToBottom() {
  await nextTick()
  if (scrollBox.value) scrollBox.value.scrollTop = scrollBox.value.scrollHeight
}
</script>

<style scoped>
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

/* ---------- 选择类型 ---------- */
.entry {
  flex: 1;
  padding: 40px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.entry h1 {
  margin: 0 0 10px;
  font-size: 22px;
  color: var(--text-main);
}

.sub {
  margin: 0 0 26px;
  font-size: 13px;
  color: var(--text-muted);
  text-align: center;
  line-height: 1.7;
  max-width: 480px;
}

.types {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  justify-content: center;
  width: 100%;
  margin-bottom: 26px;
}

.type {
  position: relative;
  min-width: 190px;
  padding: 16px 20px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  cursor: pointer;
  font-size: 15px;
  color: var(--text-body);
  transition: all 0.15s;
  font-family: inherit;
}

.type:hover {
  border-color: #b9d4f7;
  background: #f7fbff;
}

.type.active {
  border-color: var(--brand);
  background: #f0f7ff;
  color: var(--brand-dark);
  font-weight: 600;
  box-shadow: 0 0 0 3px rgba(47, 128, 237, 0.1);
}

.type-check {
  position: absolute;
  top: 8px;
  right: 10px;
  font-size: 12px;
  color: var(--brand);
}

.start {
  min-width: 180px;
}

/* ---------- 对话 ---------- */
.chat {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.chat-head {
  height: 50px;
  flex: 0 0 50px;
  border-bottom: 1px solid var(--line);
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 20px;
  font-size: 14px;
  color: var(--text-muted);
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #2ecc71;
}

.who {
  color: var(--text-main);
  font-weight: 600;
}

.restart {
  margin-left: auto;
}

.messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 20px 20px 8px;
  background: #fafbfc;
}

.msg {
  display: flex;
  margin-bottom: 12px;
}

.msg.user {
  justify-content: flex-end;
}

.bubble {
  max-width: 78%;
  padding: 10px 14px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid var(--line);
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.msg.user .bubble {
  background: var(--brand);
  border-color: var(--brand);
  color: #fff;
}

.thinking {
  color: var(--text-muted);
}

.input {
  display: flex;
  gap: 10px;
  padding: 12px 16px 6px;
  border-top: 1px solid var(--line);
  align-items: flex-end;
}

.send {
  height: 54px;
  padding: 0 22px;
}

.hint {
  padding: 0 16px 10px;
  font-size: 12px;
  color: #c0c4cc;
}

/* ---------- 待确认 ---------- */
.confirm {
  margin: 0 16px 12px;
  padding: 14px 16px;
  border: 1px solid #f3e2c7;
  background: #fffaf1;
  border-radius: 8px;
}

.confirm-title {
  font-size: 14px;
  font-weight: 700;
  color: #a86b1a;
  margin-bottom: 10px;
}

.confirm-text :deep(textarea) {
  background: #fff;
  font-size: 14px;
  line-height: 1.7;
  color: #303133;
}

.confirm-missing {
  margin-top: 10px;
  font-size: 13px;
  color: #a86b1a;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.confirm-actions {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.confirm-tip {
  font-size: 12px;
  color: #b08a52;
}

/* ---------- 出单 ---------- */
.done {
  margin: 0 16px 16px;
  padding: 16px 18px;
  border: 1px solid #d6ecd9;
  background: #f2fbf4;
  border-radius: 8px;
}

.done.returned {
  border-color: #f3e2c7;
  background: #fffaf1;
}

.done.returned .done-title {
  color: #a86b1a;
}

.done.returned .done-body {
  color: #8a6a3a;
}

.done-title {
  font-size: 15px;
  font-weight: 700;
  color: #2e7d4f;
  margin-bottom: 4px;
}

.done-body {
  font-size: 13px;
  color: #4a6b56;
  margin-bottom: 8px;
}

.done-wait {
  font-size: 12px;
  color: #7a9c86;
  margin-bottom: 10px;
}

@media (max-width: 720px) {
  .entry { padding: 28px 20px; }
  .type { min-width: 100%; }
}
</style>
