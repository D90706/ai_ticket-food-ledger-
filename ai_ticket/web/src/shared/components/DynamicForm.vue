<template>
  <div class="dynamic-form">
    <div v-for="field in fields" :key="field.key" class="row">
      <div class="label">
        {{ field.label || field.key }}
        <el-tag v-if="field.required" size="small" type="danger" effect="plain">必填</el-tag>
        <el-tag v-else size="small" effect="plain">选填</el-tag>
      </div>

      <div class="value">
        <template v-if="isEmpty(values[field.key])">
          <span class="empty">—</span>
        </template>
        <template v-else-if="field.type === 'boolean'">
          <el-tag :type="values[field.key] ? 'warning' : 'success'" size="small">
            {{ values[field.key] ? '是' : '否' }}
          </el-tag>
        </template>
        <template v-else-if="field.type === 'image'">
          <span class="empty">需人工补充</span>
        </template>
        <template v-else>
          <span class="text">{{ values[field.key] }}</span>
        </template>

        <!-- 依据常驻显示，红黄绿一视同仁：
             绿色只说明信息齐全，不说明判断正确，误读只能靠看依据发现 -->
        <div v-if="basis && basis[field.key]" class="basis">
          依据：{{ basis[field.key] }}
        </div>
      </div>
    </div>
    <el-empty v-if="!fields || !fields.length" description="模板没有字段" :image-size="60" />
  </div>
</template>

<script setup>
defineProps({
  fields: { type: Array, default: () => [] },
  values: { type: Object, default: () => ({}) },
  basis: { type: Object, default: () => ({}) }
})

function isEmpty(v) {
  return v === null || v === undefined || v === ''
}
</script>

<style scoped>
.row {
  display: flex;
  gap: 16px;
  padding: 10px 0;
  border-bottom: 1px dashed #ebeef5;
}
.label {
  width: 200px;
  flex-shrink: 0;
  color: #606266;
  font-size: 14px;
}
.label .el-tag {
  margin-left: 6px;
}
.value {
  flex: 1;
  font-size: 14px;
  color: #303133;
  word-break: break-all;
}
.empty {
  color: #c0c4cc;
}
.basis {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
</style>
