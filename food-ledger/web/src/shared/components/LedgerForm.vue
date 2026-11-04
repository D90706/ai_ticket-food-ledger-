<template>
  <div class="ledger-form">
    <div class="group-title">法定项目 <span class="hint">《食品安全法》第五十三条要求记录的项目</span></div>
    <el-form label-width="120px" size="default">
      <el-form-item v-for="f in legalFields" :key="f.key"
                    :label="f.label" :required="f.required">
        <el-select v-if="f.type === 'enum'" v-model="model[f.key]" clearable :disabled="disabled"
                   :placeholder="f.hint || '请选择'" style="width: 100%">
          <el-option v-for="o in f.options || []" :key="o" :label="o" :value="o" />
        </el-select>
        <el-date-picker v-else-if="f.type === 'date'" v-model="model[f.key]" type="date" :disabled="disabled"
                        value-format="YYYY-MM-DD" :placeholder="f.hint || '选择日期'"
                        style="width: 100%" />
        <el-input-number v-else-if="f.type === 'number'" v-model="model[f.key]"
                         :controls="false" :disabled="disabled" :placeholder="f.hint || ''" style="width: 100%" />
        <el-switch v-else-if="f.type === 'boolean'" v-model="model[f.key]" :disabled="disabled" />
        <el-input v-else v-model="model[f.key]" :disabled="disabled" :placeholder="f.hint || ''" clearable />
        <div v-if="f.hint && f.type !== 'text'" class="field-hint">{{ f.hint }}</div>
      </el-form-item>
    </el-form>

    <div class="group-title">
      扩展字段 <span class="hint">本单位自己加的，可以都不填</span>
    </div>
    <el-form label-width="120px" size="default">
      <el-form-item v-for="f in customFields" :key="f.key" :label="f.label">
        <el-date-picker v-if="f.type === 'date'" v-model="model[f.key]" type="date" :disabled="disabled"
                        value-format="YYYY-MM-DD" style="width: 100%" />
        <el-input-number v-else-if="f.type === 'number'" v-model="model[f.key]"
                         :controls="false" :disabled="disabled" style="width: 100%" />
        <el-input v-else v-model="model[f.key]" :disabled="disabled" :placeholder="f.hint || ''" clearable />
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 按模板渲染的录入表单。
 *
 * 字段、必填、可选值、提示文案全部来自模板接口，
 * 组件本身不认识"订单号"或"批号"这种业务字段。
 * 这样加一个自定义字段只需要改模板数据。
 */
const props = defineProps({
  fields: { type: Array, default: () => [] },
  /** 只读时禁用全部控件（已入账/已作废的记录不许改） */
  disabled: { type: Boolean, default: false }
})

const model = defineModel({ type: Object, default: () => ({}) })

const legalFields = computed(() => props.fields.filter((f) => f.fixed))
const customFields = computed(() => props.fields.filter((f) => !f.fixed))
</script>

<style scoped>
.group-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-main);
  margin: 6px 0 12px;
  padding-left: 8px;
  border-left: 3px solid var(--brand);
}
.group-title .hint { font-weight: 400; font-size: 12px; color: var(--text-muted); margin-left: 8px; }
.field-hint { font-size: 11px; color: var(--text-muted); line-height: 1.4; margin-top: 2px; }
</style>