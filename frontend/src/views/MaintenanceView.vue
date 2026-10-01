<template>
  <div>
    <!-- 费用统计 -->
    <div class="sum-grid" style="margin-bottom: 14px">
      <div class="sum-card"><div class="label">维修单总数</div><div class="value">{{ summary.total }}</div></div>
      <div class="sum-card"><div class="label">待修</div><div class="value">{{ summary.waiting }}</div></div>
      <div class="sum-card accent"><div class="label">维修中</div><div class="value">{{ summary.repairing }}</div></div>
      <div class="sum-card"><div class="label">已完工</div><div class="value">{{ summary.done }}</div></div>
      <div class="sum-card accent"><div class="label">配件费合计</div><div class="value">¥{{ summary.partsFeeTotal }}</div></div>
      <div class="sum-card accent"><div class="label">工时费合计</div><div class="value">¥{{ summary.laborFeeTotal }}</div></div>
      <div class="sum-card accent"><div class="label">总费用</div><div class="value">¥{{ summary.totalFee }}</div></div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">维修保养单</span>
        <span class="panel-extra">总费用 = 工时 × 80 元/小时 + 配件费</span>
        <el-button type="danger" size="small" style="margin-left: auto" @click="dialog = true">＋ 开维修单</el-button>
      </div>
      <el-table :data="rows" size="small" stripe>
        <el-table-column prop="orderNo" label="维修单号" width="120" />
        <el-table-column prop="kartNo" label="车号" width="80" />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ row.maintTypeName }}</template>
        </el-table-column>
        <el-table-column prop="faultDesc" label="故障描述" min-width="200" show-overflow-tooltip />
        <el-table-column prop="workHours" label="工时" width="70" />
        <el-table-column label="配件费" width="90">
          <template #default="{ row }">¥{{ row.partsFee }}</template>
        </el-table-column>
        <el-table-column label="总费用" width="100">
          <template #default="{ row }"><b>¥{{ row.totalFee }}</b></template>
        </el-table-column>
        <el-table-column prop="worker" label="维修人" width="90" />
        <el-table-column prop="startTime" label="开始时间" width="165" />
        <el-table-column prop="endTime" label="结束时间" width="165" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <span class="tag" :class="row.status === 'DONE' ? 'ok' : row.status === 'REPAIRING' ? 'warn' : 'mute'">
              {{ row.statusName }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'WAITING'"
              link
              type="warning"
              :loading="operationKey === `start-${row.id}`"
              @click="start(row)"
            >
              开工
            </el-button>
            <el-button
              v-if="row.status === 'REPAIRING'"
              link
              type="success"
              :loading="operationKey === `complete-${row.id}`"
              @click="complete(row)"
            >
              完工
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="开立维修单" width="440">
      <el-form label-width="90px">
        <el-form-item label="维修单号"><el-input v-model="form.orderNo" placeholder="例如 MT-20260913" /></el-form-item>
        <el-form-item label="车辆">
          <el-select v-model="form.kartId" style="width: 100%">
            <el-option
              v-for="k in karts"
              :key="k.id"
              :label="kartOptionLabel(k)"
              :value="k.id"
            />
          </el-select>
        </el-form-item>
        <el-alert
          v-if="selectedKart"
          style="margin: -8px 0 18px 90px"
          :type="selectedKart.status === 'ON_TRACK' ? 'error' : openCount(selectedKart.id) > 0 ? 'warning' : 'info'"
          show-icon
          :closable="false"
          :title="createAlertText(selectedKart)"
        />
        <el-form-item label="维修类型">
          <el-select v-model="form.maintType" style="width: 100%">
            <el-option label="换机油" value="OIL" />
            <el-option label="换轮胎" value="TIRE" />
            <el-option label="刹车片" value="BRAKE" />
            <el-option label="链条" value="CHAIN" />
            <el-option label="火花塞" value="SPARK" />
            <el-option label="发动机大修" value="ENGINE" />
            <el-option label="车身修复" value="BODY" />
          </el-select>
        </el-form-item>
        <el-form-item label="故障描述"><el-input v-model="form.faultDesc" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="工时"><el-input v-model.number="form.workHours" /></el-form-item>
        <el-form-item label="配件费"><el-input v-model.number="form.partsFee" /></el-form-item>
        <el-form-item label="维修人"><el-input v-model="form.worker" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="danger" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { kartApi, maintenanceApi } from '../api'

const track = inject('track')
const rows = ref([])
const karts = ref([])
const summary = ref({ total: 0, waiting: 0, repairing: 0, done: 0, partsFeeTotal: 0, laborFeeTotal: 0, totalFee: 0 })
const dialog = ref(false)
const saving = ref(false)
const operationKey = ref('')
const initialForm = () => ({ orderNo: '', kartId: null, maintType: 'OIL', faultDesc: '', workHours: 1, partsFee: 180, worker: '' })
const form = ref(initialForm())

const selectedKart = computed(() => karts.value.find((k) => k.id === form.value.kartId))

async function load() {
  rows.value = await maintenanceApi.list()
  summary.value = await maintenanceApi.summary()
  karts.value = await kartApi.list()
}

function kartOptionLabel(kart) {
  const statusMap = {
    AVAILABLE: '可用',
    ON_TRACK: '在赛',
    MAINTENANCE: '维修',
    DISABLED: '停用'
  }
  return `${kart.kartNo} · ${kart.modelName} · ${statusMap[kart.status] || kart.status}`
}

function waitingCount(kartId) {
  return rows.value.filter((r) => r.kartId === kartId && r.status === 'WAITING').length
}

function repairingCount(kartId) {
  return rows.value.filter((r) => r.kartId === kartId && r.status === 'REPAIRING').length
}

function openCount(kartId) {
  return waitingCount(kartId) + repairingCount(kartId)
}

function createAlertText(kart) {
  if (kart.status === 'ON_TRACK') {
    return `${kart.kartNo} 正在赛道上，不能直接开立维修单；请先收车或在红旗核查中送修。`
  }
  if (openCount(kart.id) > 0) {
    return `${kart.kartNo} 当前有 ${openCount(kart.id)} 张未结工单（维修中 ${repairingCount(kart.id)}、待修 ${waitingCount(kart.id)}）。新开待修单后车辆继续锁定；任一张工单完工时，仍需确认同车没有其他待修或维修中工单，车辆才会恢复可用。`
  }
  return `开立待修单后 ${kart.kartNo} 将转入维修；全部未结工单完工后才会恢复可用。`
}

async function start(row) {
  operationKey.value = `start-${row.id}`
  try {
    let worker = row.worker && row.worker.trim()
    if (!worker) {
      const result = await ElMessageBox.prompt(`请输入 ${row.orderNo} 的维修人`, '开工', {
        confirmButtonText: '开工',
        cancelButtonText: '取消',
        inputValidator: (value) => Boolean(value && value.trim()) || '请填写维修人'
      })
      worker = result.value.trim()
    }

    await maintenanceApi.start(row.id, worker)
    await load()
    await track.refreshOverview()
  } catch (err) {
    if (err !== 'cancel' && err !== 'close') {
      await load()
      await track.refreshOverview()
    }
  } finally {
    operationKey.value = ''
  }
}

async function complete(row) {
  operationKey.value = `complete-${row.id}`
  try {
    await maintenanceApi.complete(row.id)
    await load()
    await track.refreshOverview()
  } catch (err) {
    await load()
    await track.refreshOverview()
  } finally {
    operationKey.value = ''
  }
}

async function submit() {
  saving.value = true
  try {
    await maintenanceApi.create(form.value)
    dialog.value = false
    form.value = initialForm()
    await load()
    await track.refreshOverview()
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>
