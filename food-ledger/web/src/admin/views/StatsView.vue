<template>
  <div class="wrap">
    <el-card shadow="never">
      <div class="bar">
        <span class="title">台账统计</span>
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD"
                        start-placeholder="进货日期起" end-placeholder="进货日期止"
                        style="width: 280px" @change="load" />
        <el-button :loading="loading" @click="load">刷新</el-button>
        <span class="tip">
          「总金额」不含已作废；「已入账金额」只算审核通过的
        </span>
      </div>
    </el-card>

    <div class="cards" v-loading="loading">
      <div class="card">
        <div class="k">记录数</div>
        <div class="v">{{ data.totalRecords }}</div>
        <div class="s">已入账 {{ data.postedCount }} · 待入账 {{ data.pendingCount }}</div>
      </div>
      <div class="card">
        <div class="k">采购金额</div>
        <div class="v">{{ money(data.totalAmount) }}</div>
        <div class="s">其中已入账 {{ money(data.postedAmount) }}</div>
      </div>
      <div class="card">
        <div class="k">合规分布</div>
        <div class="v">
          <span class="dot red"></span>{{ data.redCount }}
          <span class="dot yellow"></span>{{ data.yellowCount }}
          <span class="dot green"></span>{{ data.greenCount }}
        </div>
        <div class="s">不合规 {{ data.redCount }} · 待补证 {{ data.yellowCount }} · 完整 {{ data.greenCount }}</div>
      </div>
      <div class="card">
        <div class="k">临期 / 过期</div>
        <div class="v" :class="{ danger: data.expiringCount > 0 }">{{ data.expiringCount }}</div>
        <div class="s">30 天内到期或已过期</div>
      </div>
    </div>

    <div class="grid">
      <el-card shadow="never">
        <template #header><span class="card-title">按月采购金额</span></template>
        <table class="tbl" v-if="data.byMonth?.length">
          <thead><tr><th>月份</th><th class="num">笔数</th><th class="num">金额</th><th>占比</th></tr></thead>
          <tbody>
            <tr v-for="m in data.byMonth" :key="m.month">
              <td>{{ m.month }}</td>
              <td class="num">{{ m.count }}</td>
              <td class="num">{{ money(m.amount) }}</td>
              <td>
                <div class="bar-bg"><div class="bar-fill" :style="{ width: pctOf(m.amount) }"></div></div>
              </td>
            </tr>
          </tbody>
        </table>
        <el-empty v-else description="这段时间没有记录" :image-size="60" />
      </el-card>

      <el-card shadow="never">
        <template #header><span class="card-title">供应商金额 Top {{ data.bySupplier?.length || 8 }}</span></template>
        <table class="tbl" v-if="data.bySupplier?.length">
          <thead><tr><th>供货者</th><th class="num">笔数</th><th class="num">金额</th><th>占比</th></tr></thead>
          <tbody>
            <tr v-for="s in data.bySupplier" :key="s.name">
              <td>{{ s.name }}</td>
              <td class="num">{{ s.count }}</td>
              <td class="num">{{ money(s.amount) }}</td>
              <td>
                <div class="bar-bg"><div class="bar-fill" :style="{ width: pctOf(s.amount) }"></div></div>
              </td>
            </tr>
          </tbody>
        </table>
        <el-empty v-else description="暂无数据" :image-size="60" />
      </el-card>

      <el-card shadow="never">
        <template #header><span class="card-title">品类金额分布</span></template>
        <table class="tbl" v-if="data.byCategory?.length">
          <thead><tr><th>品类</th><th class="num">笔数</th><th class="num">金额</th><th>占比</th></tr></thead>
          <tbody>
            <tr v-for="c in data.byCategory" :key="c.name">
              <td>{{ c.name }}</td>
              <td class="num">{{ c.count }}</td>
              <td class="num">{{ money(c.amount) }}</td>
              <td>
                <div class="bar-bg"><div class="bar-fill" :style="{ width: pctOf(c.amount) }"></div></div>
              </td>
            </tr>
          </tbody>
        </table>
        <el-empty v-else description="暂无数据" :image-size="60" />
      </el-card>

      <el-card shadow="never">
        <template #header><span class="card-title">临期与过期明细</span></template>
        <table class="tbl" v-if="data.expiring?.length">
          <thead><tr><th>#</th><th>名称</th><th>批号</th><th>到期日</th><th>剩余</th></tr></thead>
          <tbody>
            <tr v-for="e in data.expiring" :key="e.id">
              <td>{{ e.id }}</td>
              <td>{{ e.ingredientName }}</td>
              <td class="dim">{{ e.batchNo || '—' }}</td>
              <td>{{ e.expiryDate }}</td>
              <td :class="e.daysLeft < 0 ? 'danger' : 'warn'">
                {{ e.daysLeft < 0 ? `已过期 ${-e.daysLeft} 天` : `剩 ${e.daysLeft} 天` }}
              </td>
            </tr>
          </tbody>
        </table>
        <el-empty v-else description="没有临期或过期食材" :image-size="60" />
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import http from '../../shared/api/http'

/**
 * 统计页。
 *
 * 不引图表库：一个课程设计为了两根柱状图引 echarts，
 * 换来的是 1MB 的打包体积和一个新的学习成本。
 * 用 div 的宽度做条形，够用且打印友好。
 */
const loading = ref(false)
const range = ref(null)
const data = ref({
  totalRecords: 0, totalAmount: 0, postedAmount: 0, postedCount: 0,
  pendingCount: 0, returnedCount: 0, voidCount: 0,
  redCount: 0, yellowCount: 0, greenCount: 0, expiringCount: 0,
  byMonth: [], bySupplier: [], byCategory: [], expiring: []
})

onMounted(load)

async function load() {
  loading.value = true
  try {
    const params = {}
    if (range.value?.length === 2) {
      params.dateFrom = range.value[0]
      params.dateTo = range.value[1]
    }
    data.value = await http.get('/stats/overview', { params })
  } finally {
    loading.value = false
  }
}

function money(v) {
  const n = Number(v || 0)
  return '¥' + n.toFixed(2)
}

/** 相对最大值的百分比：看的是"谁多谁少"，不是绝对刻度 */
function pctOf(amount) {
  const all = [
    ...(data.value.byMonth || []),
    ...(data.value.bySupplier || []),
    ...(data.value.byCategory || [])
  ].map((x) => Number(x.amount || 0))
  const max = Math.max(1, ...all)
  return Math.max(2, (Number(amount || 0) / max) * 100) + '%'
}
</script>

<style scoped>
.wrap { width: 100%; max-width: 1180px; }
.bar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.title { font-weight: 700; color: var(--text-main); }
.tip { margin-left: auto; font-size: 12px; color: var(--text-muted); }
.cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-top: 12px; }
.card { background: #fff; border-radius: 8px; padding: 16px 18px; box-shadow: 0 1px 3px rgba(0,0,0,.06); }
.card .k { font-size: 12px; color: var(--text-muted); }
.card .v { font-size: 24px; font-weight: 700; color: var(--text-main); margin: 6px 0 4px; }
.card .s { font-size: 12px; color: var(--text-muted); }
.card .v.danger { color: var(--red); }
.dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin: 0 4px 0 8px; }
.dot:first-child { margin-left: 0; }
.dot.red { background: var(--red); }
.dot.yellow { background: var(--yellow); }
.dot.green { background: var(--green); }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 12px; }
.card-title { font-weight: 700; font-size: 13px; color: var(--text-main); }
.tbl { width: 100%; border-collapse: collapse; font-size: 13px; }
.tbl th, .tbl td { padding: 7px 8px; border-bottom: 1px solid var(--line); text-align: left; }
.tbl th { font-weight: 600; color: var(--text-muted); font-size: 12px; }
.tbl .num { text-align: right; }
.tbl .dim { color: var(--text-muted); }
.tbl .danger { color: var(--red); font-weight: 600; }
.tbl .warn { color: var(--yellow); font-weight: 600; }
.bar-bg { background: #eef2ee; border-radius: 3px; height: 8px; width: 100%; }
.bar-fill { background: var(--brand); height: 8px; border-radius: 3px; }
</style>
