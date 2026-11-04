<template>
  <el-tag :type="typeOf" size="small" effect="plain">{{ label }}</el-tag>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 合规级别标签。
 *
 * 颜色必须配文字：台账报表要黑白打印，
 * 只靠颜色分级，打印出来等级就消失了。
 */
const props = defineProps({ level: { type: String, default: '' } })

const MAP = {
  RED: { label: '不合规', type: 'danger' },
  YELLOW: { label: '待补证', type: 'warning' },
  GREEN: { label: '记录完整', type: 'success' }
}

const label = computed(() => MAP[props.level]?.label || props.level || '—')
const typeOf = computed(() => MAP[props.level]?.type || 'info')
</script>