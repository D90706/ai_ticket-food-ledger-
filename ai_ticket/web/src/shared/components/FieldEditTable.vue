<template>
  <div>
    <el-table :data="fields" size="small" border>
      <el-table-column label="key" width="130">
        <template #default="{ row }">
          <el-input v-model="row.key" size="small" placeholder="order_id" />
        </template>
      </el-table-column>

      <el-table-column label="中文名" width="150">
        <template #default="{ row }">
          <el-input v-model="row.label" size="small" placeholder="订单号" />
        </template>
      </el-table-column>

      <el-table-column label="类型" width="120">
        <template #default="{ row }">
          <el-select v-model="row.type" size="small">
            <el-option v-for="t in TYPES" :key="t" :label="t" :value="t" />
          </el-select>
        </template>
      </el-table-column>

      <el-table-column label="必填" width="80" align="center">
        <template #default="{ row }">
          <!-- required 必须是显式布尔值：后端会拒绝 null，
               这是「必填/选填不允许默认」那条设计规则的落点 -->
          <el-switch v-model="row.required" size="small" />
        </template>
      </el-table-column>

      <el-table-column label="枚举候选（逗号分隔）" width="220">
        <template #default="{ row }">
          <el-input
            :model-value="(row.options || []).join(',')"
            :disabled="row.type !== 'enum'"
            size="small"
            placeholder="尺码不合适,质量问题"
            @update:model-value="(v) => (row.options = splitOptions(v))"
          />
        </template>
      </el-table-column>

      <el-table-column label="格式正则（仅 text）" width="200">
        <template #default="{ row }">
          <el-input
            v-model="row.pattern"
            :disabled="row.type !== 'text'"
            size="small"
            placeholder="^\d{8,20}$"
          />
        </template>
      </el-table-column>

      <el-table-column label="操作" width="150" align="center">
        <template #default="{ $index }">
          <el-button link size="small" :disabled="$index === 0" @click="move($index, -1)">
            上移
          </el-button>
          <el-button link size="small" :disabled="$index === fields.length - 1"
                     @click="move($index, 1)">
            下移
          </el-button>
          <el-button link type="danger" size="small" @click="remove($index)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-button class="add" type="primary" plain size="small" @click="add">+ 添加字段</el-button>
    <div class="tip">
      提示：pattern 能挡住编造，也会吞掉真实信息——过严的正则会让模型改成输出 null，
      结果是字段变空、工单掉红。加之前先核对真实数据格式。
    </div>
  </div>
</template>

<script setup>
const props = defineProps({
  fields: { type: Array, required: true }
})

const TYPES = ['text', 'enum', 'number', 'boolean', 'date', 'image']

function splitOptions(v) {
  return (v || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
}

function add() {
  props.fields.push({
    key: '',
    label: '',
    type: 'text',
    required: false,
    options: null,
    pattern: null
  })
}

function remove(index) {
  props.fields.splice(index, 1)
}

function move(index, offset) {
  const target = index + offset
  if (target < 0 || target >= props.fields.length) return
  const [item] = props.fields.splice(index, 1)
  props.fields.splice(target, 0, item)
}
</script>

<style scoped>
.add {
  margin-top: 12px;
}
.tip {
  margin-top: 10px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
</style>
