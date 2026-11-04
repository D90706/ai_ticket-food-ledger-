<template>
  <div class="banner" :class="levelClass">
    <div class="head">
      <span class="dot"></span>
      <span class="label">{{ levelText }}</span>
      <span class="hint">{{ hint }}</span>
    </div>
    <ul v-if="messages && messages.length" class="messages">
      <li v-for="(m, i) in messages" :key="i">{{ m }}</li>
    </ul>
  </div>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 级别提示条。
 *
 * 三条设计规则落在这里：
 *   1 颜色绑定的是「能不能直接决策」，不是分数高低
 *   2 必须配文字与图标——报告要黑白打印，纯颜色分级在纸上会消失
 *   3 显示的是「缺哪几项」，不是一个分数
 */
const props = defineProps({
  level: { type: String, default: '' },
  messages: { type: Array, default: () => [] }
})

const levelClass = computed(() => {
  if (props.level === 'RED') return 'red'
  if (props.level === 'YELLOW') return 'yellow'
  if (props.level === 'GREEN') return 'green'
  return 'plain'
})

const levelText = computed(() => {
  if (props.level === 'RED') return '🔴 信息不全'
  if (props.level === 'YELLOW') return '🟡 待核实'
  if (props.level === 'GREEN') return '🟢 可直接决策'
  return '—'
})

const hint = computed(() => {
  if (props.level === 'RED') return '不可直接决策，需先补信息'
  if (props.level === 'YELLOW') return '可决策，但请先核对依据'
  if (props.level === 'GREEN') return '信息齐全、依据可溯源'
  return ''
})
</script>

<style scoped>
.banner {
  border-radius: 6px;
  padding: 10px 14px;
  margin-bottom: 16px;
  border-left: 6px solid #909399;
  background: #f4f4f5;
}
.banner.red {
  border-left-color: #f56c6c;
  background: #fef0f0;
}
.banner.yellow {
  border-left-color: #e6a23c;
  background: #fdf6ec;
}
.banner.green {
  border-left-color: #67c23a;
  background: #f0f9eb;
}
.head {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.label {
  font-weight: 600;
}
.hint {
  color: #606266;
  font-size: 13px;
}
.messages {
  margin: 8px 0 0 18px;
  padding: 0;
  font-size: 13px;
  color: #303133;
}
.messages li {
  margin-bottom: 2px;
}
</style>
