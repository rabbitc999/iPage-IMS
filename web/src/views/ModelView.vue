<script setup>
/**
 * 通用列表页（{@code /m/:key}），全部模型共用。
 * <p>
 * 渲染完全由 {@code GET /api/models/{key}/schema} 驱动：
 * 哪些列显示、能否新增/编辑/删除、有哪些筛选项，都取服务端的布尔量与字段定义。
 * <p>
 * 列显隐与每页条数按用户保存到 {@code /api/views/{key}}，换账号不串数据。
 */
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, upload } from '../api/http'
import { toast, toastError } from '../api/toast'
import DataForm from '../components/DataForm.vue'

const route = useRoute()
const router = useRouter()
const key = computed(() => route.params.key)

const schema = ref(null)
const rows = ref([])
const total = ref(0)
const pages = ref(0)
const page = ref(1)
const size = ref(20)
/** 当前账号在「列设置」里保存的每页条数；URL 没带 size 时用它。 */
const viewSize = ref(20)
const sort = ref('')
const order = ref('desc')
const kw = ref('')
const filters = ref([])
const hidden = ref([])
const loading = ref(false)
const error = ref('')
const selected = ref([])
const columnDialog = ref(false)
const attachmentDialog = ref(null)
const attachmentBusy = ref(false)
const supportsAttachments = computed(() => schema.value?.supportsAttachments === true)

async function openAttachments(row) {
  const path = `/models/${key.value}/${row.id}/attachments`
  const panel = { path, files: [], loading: true }
  attachmentDialog.value = panel
  try {
    const files = await api(path)
    if (attachmentDialog.value?.path === path) attachmentDialog.value = { path, files, loading: false }
  } catch (error) {
    if (attachmentDialog.value?.path === path) attachmentDialog.value = null
    toastError(error)
  }
}

async function downloadAttachment(file) {
  try {
    const blob = await api(`${attachmentDialog.value.path}/${file.id}/download`, { responseType: 'blob' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = file.name
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (error) {
    toastError(error)
  }
}

async function detachAttachment(file) {
  if (attachmentBusy.value || !window.confirm(`解除「${file.name}」与此记录的关联？`)) return
  const panel = attachmentDialog.value
  attachmentBusy.value = true
  try {
    await api(`${panel.path}/${file.id}`, { method: 'DELETE' })
    panel.files = panel.files.filter(item => item.id !== file.id)
  } catch (error) {
    toastError(error)
  } finally {
    attachmentBusy.value = false
  }
}
const pendingVisible = ref([])
const pendingSize = ref(20)

const dialog = reactive({ open: false, mode: 'create', row: null, submitting: false })

const fields = computed(() => schema.value?.fields || [])
const tableFields = computed(() => fields.value.filter((field) => field.table))
const visibleFields = computed(() => tableFields.value.filter((field) => !hidden.value.includes(field.name)))
const filterFields = computed(() => fields.value.filter((field) =>
  field.type !== 'ASSOCIATION_MODEL' && field.type !== 'AGGREGATION' && field.type !== 'MODEL_ID'))
const canCreate = computed(() => schema.value?.permissions?.create)
const canEdit = computed(() => schema.value?.permissions?.edit)
const canDelete = computed(() => schema.value?.permissions?.delete)
const allChecked = computed(() => rows.value.length > 0 && selected.value.length === rows.value.length)

/** 按 Java 字段名取字段元数据；筛选行要靠它判断字段类型。 */
function fieldMeta(name) {
  return fields.value.find((field) => field.name === name)
}

/** 外键列存的是 UUID，只能从下拉里选具体记录。 */
function isAssociation(name) {
  return fieldMeta(name)?.type === 'ASSOCIATION_ID'
}

/** 枚举列存的是常量名（OPEN），界面要显示中文标签。 */
function isEnum(name) {
  return fieldMeta(name)?.type === 'ENUM'
}

/** 布尔列用「是/否」下拉，避免把「是」当成 false。 */
function isBoolean(name) {
  return fieldMeta(name)?.type === 'BOOLEAN'
}

/** 只有字符串列适合「包含/开头/结尾」；日期、数字、布尔都不该走 LIKE。 */
function isFuzzy(name) {
  return fieldMeta(name)?.type === 'STRING'
}

/** 外键、枚举、布尔都只能选、不能手打。 */
function needsSelect(name) {
  return isAssociation(name) || isEnum(name) || isBoolean(name)
}

function selectOptions(name) {
  if (isBoolean(name)) return { true: '是', false: '否' }
  return fieldMeta(name)?.options || {}
}

function defaultOperator(name) {
  return isFuzzy(name) && !needsSelect(name) ? 'LIKE' : 'EQ'
}

/** 切换筛选字段时，把操作符和值调整成该字段能用的形态。 */
function onFilterFieldChange(filter) {
  filter.op = defaultOperator(filter.field)
  filter.value = ''
  search()
}

/** 地址栏里的旧条件可能带着不支持的 LIKE（例如日期列），还原时纠正掉。 */
function normalizeFilterOp(filter) {
  const meta = fieldMeta(filter.field)
  if (!meta) return filter
  if (!isFuzzy(meta.name) && ['LIKE', 'STARTS_WITH', 'ENDS_WITH'].includes(filter.op)) {
    filter.op = 'EQ'
  }
  return filter
}

// ------------------------------------------------------------------ 查询参数

/**
 * 拼装查询参数。
 * <p>
 * 约定：`f_{字段}` 传值、`op_{字段}` 传操作符，字段名必须是 Java 字段名（不是列名）；
 * 空值一律不发，避免服务端把空串当成"查空字符串"。
 *
 * @returns {object} 查询参数
 */
/** Transfers use the same authenticated API and filters as the list. */
const transferFormat = ref('csv')
const transferring = ref(false)
/**
 * 导出与「下载导入模板」共用的文件格式。
 * <p>
 * 只有两个取值，直接摊成两个按钮比裸 `<select>` 更清楚：
 * 用户一眼能看出这个控件管的是「文件格式」，而不是某个按钮的附属品。
 */
const transferFormats = [
  { value: 'csv', label: 'CSV' },
  { value: 'xlsx', label: 'Excel' }
]

async function downloadTable(template = false) {
  const model = key.value
  const format = transferFormat.value
  transferring.value = true
  try {
    const blob = await api(`/models/${model}/transfer/export`, {
      params: { ...params(), format, template },
      responseType: 'blob'
    })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${model}${template ? '-template' : ''}.${format}`
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (error) {
    toastError(error)
  } finally {
    transferring.value = false
  }
}

async function importTable(event) {
  const file = event.target.files[0]
  event.target.value = ''
  if (!file) return
  const model = key.value
  transferring.value = true
  try {
    const result = await upload(`/models/${model}/transfer/import`, file)
    toast(`已导入 ${result.imported} 条记录`, 'success')
    if (key.value === model) await loadRows()
  } catch (error) {
    toastError(error)
  } finally {
    transferring.value = false
  }
}

function params() {
  const result = { page: page.value, size: size.value }
  if (key.value === 'ticket') {
    for (const filter of ['from', 'to', 'department']) {
      if (typeof route.query[filter] === 'string') result[filter] = route.query[filter]
    }
  }
  if (kw.value.trim()) result.kw = kw.value.trim()
  if (sort.value) {
    result.sort = sort.value
    result.order = order.value
  }
  for (const filter of filters.value) {
    const unary = ['IS_NULL', 'IS_NOT_NULL'].includes(filter.op)
    if (!filter.field || (!unary && (filter.value === '' || filter.value === null))) continue
    result[`f_${filter.field}`] = filter.value
    result[`op_${filter.field}`] = filter.op
  }
  return result
}

// ------------------------------------------------------------------ URL 查询状态

/**
 * 搜索条件同步到 URL，浏览器前进/后退才能回到上一次查询。
 * <p>
 * 只把「有内容」的条件写进地址栏：空的搜索框、刚添加还没填值的筛选行不占 URL，
 * 避免地址栏越用越长。筛选行用一段 JSON 保存，保留顺序与同一字段的多个条件。
 */
function stableQuery(query) {
  return Object.keys(query)
    .sort()
    .map((name) => {
      const value = query[name]
      if (value === undefined || value === null) return ''
      return `${name}=${Array.isArray(value) ? value.join(',') : String(value)}`
    })
    .filter(Boolean)
    .join('&')
}

function positiveInt(value, fallback) {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback
}

function parseUrlFilters(value) {
  if (typeof value !== 'string' || !value) return []
  try {
    const parsed = JSON.parse(value)
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter((item) => item && typeof item.field === 'string' && typeof item.op === 'string')
      .slice(0, 20)
      .map((item) => ({
        field: item.field,
        op: item.op.toUpperCase(),
        value: item.value === undefined || item.value === null ? '' : String(item.value)
      }))
  } catch (e) {
    return []
  }
}

function stateQuery() {
  const query = {}
  if (kw.value.trim()) query.kw = kw.value.trim()
  const activeFilters = filters.value.filter((filter) => {
    const unary = ['IS_NULL', 'IS_NOT_NULL'].includes(filter.op)
    return filter.field && (unary || (filter.value !== '' && filter.value !== null && filter.value !== undefined))
  })
  if (activeFilters.length) query.filters = JSON.stringify(activeFilters)
  if (page.value > 1) query.page = String(page.value)
  if (size.value !== 20) query.size = String(size.value)
  if (sort.value) {
    query.sort = sort.value
    query.order = order.value
  }
  if (key.value === 'ticket') {
    for (const name of ['from', 'to', 'department']) {
      if (typeof route.query[name] === 'string') query[name] = route.query[name]
    }
  }
  return query
}

function applyQuery(query) {
  kw.value = typeof query.kw === 'string' ? query.kw : ''
  page.value = positiveInt(query.page, 1)
  const requestedSize = positiveInt(query.size, 0)
  size.value = requestedSize || viewSize.value || 20
  sort.value = typeof query.sort === 'string' ? query.sort : ''
  order.value = query.order === 'asc' ? 'asc' : 'desc'
  const allowed = new Set(filterFields.value.map((field) => field.name))
  filters.value = parseUrlFilters(query.filters)
    .filter((filter) => allowed.has(filter.field))
    .map(normalizeFilterOp)
  pendingSize.value = size.value
}

/** 最近一次已经反映到界面上的查询，用来区分「自己 push」和「浏览器后退」。 */
let appliedQuery = ''

function syncQuery(mode = 'push') {
  const query = stateQuery()
  const serialized = stableQuery(query)
  const current = stableQuery(route.query)
  appliedQuery = serialized
  if (current === serialized) return false
  const navigate = mode === 'replace' ? router.replace : router.push
  navigate({ query }).catch(() => {})
  return true
}

// ------------------------------------------------------------------ 数据加载

/**
 * 加载模型 schema。
 * <p>
 * 表格列、表单字段、权限布尔量全部来自这里，所以本组件不依赖任何具体模型。
 */
async function loadSchema() {
  schema.value = await api(`/models/${key.value}/schema`)
  document.title = `${schema.value.name} · 信息管理系统`
}

/**
 * 加载当前用户对这张表的界面配置（隐藏列、每页条数）。
 * <p>
 * 配置失败不影响主流程：拿不到就用默认值，绝不让页面因为"列设置读不到"而白屏。
 */
async function loadView() {
  try {
    const result = await api(`/views/${key.value}`)
    const config = result.config || {}
    hidden.value = Array.isArray(config.hidden) ? config.hidden : []
    viewSize.value = Number(config.size) > 0 ? Number(config.size) : 20
    size.value = viewSize.value
    pendingSize.value = size.value
  } catch (e) {
    hidden.value = []
  }
}

/**
 * 拉取当前页数据。
 * <p>
 * 每次加载都清空勾选：翻页后还保留上一页的选中项，批量删除会误删看不见的行。
 * 用递增的 loadVersion 丢弃过期响应：快速连续搜索时，先发的请求可能后到，
 * 不能让旧结果覆盖新结果。
 */
let loadVersion = 0

async function loadRows() {
  const version = ++loadVersion
  loading.value = true
  error.value = ''
  selected.value = []
  try {
    const result = await api(`/models/${key.value}`, { params: params() })
    if (version !== loadVersion) return
    rows.value = result.rows
    total.value = result.total
    pages.value = result.pages
    if (result.pages > 0 && result.page > result.pages) {
      // 删到最后一页、或筛选后总页数变少：回到最后一页，别停在空页。
      page.value = result.pages
      syncQuery('replace')
      await loadRows()
      return
    }
    page.value = result.page
  } catch (e) {
    if (version !== loadVersion) return
    error.value = e.message
    rows.value = []
    total.value = 0
    pages.value = 0
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

async function reload() {
  await loadRows()
}

function search() {
  page.value = 1
  syncQuery('push')
  loadRows()
}

function resetSearch() {
  kw.value = ''
  filters.value = []
  sort.value = ''
  order.value = 'desc'
  page.value = 1
  syncQuery('push')
  loadRows()
}

function addFilter() {
  const first = filterFields.value[0]
  filters.value.push({ field: first?.name || '', op: defaultOperator(first?.name), value: '' })
}

function removeFilter(index) {
  filters.value.splice(index, 1)
  search()
}

function sortBy(field) {
  if (!field.sortable) return
  if (sort.value === field.name) {
    order.value = order.value === 'asc' ? 'desc' : 'asc'
  } else {
    sort.value = field.name
    order.value = 'asc'
  }
  page.value = 1
  syncQuery('push')
  loadRows()
}

function setPage(next) {
  if (next < 1 || (pages.value && next > pages.value)) return
  page.value = next
  syncQuery('push')
  loadRows()
}

// ------------------------------------------------------------------ 表格渲染

/**
 * 取单元格显示文本。
 * <p>
 * 关联字段取 `{id,label}` 里的 label，枚举取服务端下发的 `{字段}Text`，
 * 空值统一显示为「—」，避免表格里出现空白格子。
 *
 * @param {object} row   行数据
 * @param {object} field 字段描述
 * @returns {*} 显示内容
 */
function cell(row, field) {
  const value = row[field.name]
  if (field.type === 'ASSOCIATION_MODEL') return value?.label ?? '—'
  if (field.type === 'ENUM') return row[`${field.name}Text`] || value || '—'
  if (field.type === 'BOOLEAN') return value ? '是' : '否'
  if (value === null || value === undefined || value === '') return '—'
  return value
}

// ------------------------------------------------------------------ 增改删

function openCreate() {
  dialog.mode = 'create'
  dialog.row = null
  dialog.open = true
}

function openEdit(row) {
  dialog.mode = 'edit'
  dialog.row = row
  dialog.open = true
}

/**
 * 提交表单。
 * <p>
 * 新增与编辑走不同的 HTTP 方法，主键是 UUID 字符串，直接拼进路径即可。
 * 成功后就地刷新列表，保证看到的永远是最新服务端状态（不做本地乐观更新）。
 *
 * @param {object} payload 表单载荷
 */
async function save(payload) {
  dialog.submitting = true
  try {
    if (dialog.mode === 'create') {
      await api(`/models/${key.value}`, { method: 'POST', body: payload })
      toast('已新增', 'success')
    } else {
      await api(`/models/${key.value}/${dialog.row.id}`, { method: 'PUT', body: payload })
      toast('已保存', 'success')
    }
    dialog.open = false
    await loadRows()
  } catch (e) {
    toastError(e)
  } finally {
    dialog.submitting = false
  }
}

async function remove(row) {
  if (!window.confirm(`确定删除「${row._display || row.id}」？`)) return
  try {
    await api(`/models/${key.value}/${row.id}`, { method: 'DELETE' })
    toast('已删除', 'success')
    await loadRows()
  } catch (e) {
    toastError(e)
  }
}

function toggleAll() {
  selected.value = allChecked.value ? [] : rows.value.map((row) => row.id)
}

async function removeSelected() {
  if (!selected.value.length) return
  if (!window.confirm(`确定删除选中的 ${selected.value.length} 条记录？`)) return
  try {
    const result = await api(`/models/${key.value}/batch-delete`, {
      method: 'POST',
      body: { ids: selected.value }
    })
    toast(`已删除 ${result.deleted} 条`, 'success')
    await loadRows()
  } catch (e) {
    toastError(e)
  }
}

// ------------------------------------------------------------------ 列设置

/**
 * 打开列设置弹窗。
 * <p>
 * 这里把「已隐藏的列」反算成「可见的列」，因为勾选框的语义是"显示"，
 * 而落库的是 `hidden` —— 两者反过来会让用户觉得勾选没生效。
 */
function openColumns() {
  pendingVisible.value = tableFields.value
    .filter((field) => !hidden.value.includes(field.name))
    .map((field) => field.name)
  pendingSize.value = size.value
  columnDialog.value = true
}

async function saveColumns() {
  hidden.value = tableFields.value
    .filter((field) => !pendingVisible.value.includes(field.name))
    .map((field) => field.name)
  size.value = Number(pendingSize.value) || 20
  viewSize.value = size.value
  columnDialog.value = false
  try {
    await api(`/views/${key.value}`, {
      method: 'PUT',
      body: { hidden: hidden.value, size: size.value }
    })
    toast('视图已保存', 'success')
  } catch (e) {
    toastError(e)
  }
  page.value = 1
  syncQuery('push')
  await loadRows()
}

// ------------------------------------------------------------------ 启动

/**
 * 首次进入：schema → 界面配置 → 地址栏条件 → 数据，顺序不能换。
 * <p>
 * 后一步依赖前一步：没有 schema 就不知道哪些列在表格里、筛选字段是否合法；
 * 没有界面配置就不知道该隐藏谁、默认每页多少条。
 */
async function bootstrap() {
  try {
    await loadSchema()
    await loadView()
    applyQuery(route.query)
    appliedQuery = stableQuery(route.query)
    await loadRows()
  } catch (e) {
    error.value = e.message
  }
}

watch(key, () => {
  page.value = 1
  kw.value = ''
  filters.value = []
  sort.value = ''
  attachmentDialog.value = null
  schema.value = null
  appliedQuery = ''
  bootstrap()
})

/**
 * 浏览器前进/后退只改地址栏，不重新挂载组件；这里把地址栏条件还原成
 * 界面状态并重新查询。自己 push 产生的变化已经由动作函数加载过，跳过。
 * <p>
 * 监听 {@code fullPath} 而不是 {@code route.query}：query 是对象，路由复用
 * 同一个对象时浅层 watch 可能不触发；字符串路径每次导航都会变化。
 */
watch(() => route.fullPath, () => {
  const query = route.query
  const serialized = stableQuery(query)
  if (serialized === appliedQuery) return
  appliedQuery = serialized
  applyQuery(query)
  if (schema.value) loadRows()
})

onMounted(bootstrap)

// ------------------------------------------------------------------ 表格滚动条自动隐藏

/** 表格滚动容器。 */
const tableWrap = ref(null)
/** 「正在滚动」状态的定时器：停止滚动 0.9s 后摘掉类名，滚动条随之淡出。 */
let hideTimer = null

/**
 * 滚动期间给容器挂 is-scrolling，让滚动条显形；停止后自动隐藏。
 * 滚动条本体样式见 styles.css 的 .table-wrap 段。
 */
function onTableScroll() {
  tableWrap.value?.classList.add('is-scrolling')
  clearTimeout(hideTimer)
  hideTimer = setTimeout(() => tableWrap.value?.classList.remove('is-scrolling'), 900)
}

onUnmounted(() => clearTimeout(hideTimer))
</script>

<template>
  <div v-if="schema">
    <div class="head">
      <div>
        <h2>{{ schema.name }}</h2>
        <div class="muted small">
          表 <span class="mono">{{ schema.table }}</span> · 共 {{ total }} 条
          <span v-if="schema.permissions.create || schema.permissions.edit"> · 可编辑</span>
        </div>
      </div>
      <div class="row wrap">
        <button class="btn" @click="openColumns">列设置</button>
        <button class="btn" :disabled="loading" @click="reload">刷新</button>
        <div class="format-switch" role="group" aria-label="导出与导入模板格式"
             title="导出与下载导入模板使用的文件格式">
          <span class="format-label">格式</span>
          <button
            v-for="option in transferFormats"
            :key="option.value"
            type="button"
            class="format-option"
            :class="{ active: transferFormat === option.value }"
            :aria-pressed="transferFormat === option.value"
            :disabled="transferring"
            @click="transferFormat = option.value"
          >{{ option.label }}</button>
        </div>
        <button class="btn" :disabled="transferring" @click="downloadTable()">导出</button>
        <button v-if="canCreate" class="btn" :disabled="transferring" @click="downloadTable(true)">下载导入模板</button>
        <button v-if="canCreate" class="btn" :disabled="transferring" @click="$refs.importFile.click()">导入</button>
        <input v-if="canCreate" ref="importFile" type="file" accept=".csv,.xlsx" hidden @change="importTable" />
        <button v-if="canCreate" class="btn primary" @click="openCreate">新增{{ schema.name }}</button>
      </div>
    </div>

    <div class="card toolbar">
      <div class="row wrap">
        <input v-model="kw" class="input search" placeholder="关键字搜索（回车执行）" @keyup.enter="search" />
        <button class="btn" @click="search">查询</button>
        <button class="btn ghost" @click="resetSearch">重置</button>
        <div class="grow"></div>
        <button class="btn sm" @click="addFilter">+ 条件</button>
        <button v-if="canDelete" class="btn sm danger" :disabled="!selected.length" @click="removeSelected">
          删除选中（{{ selected.length }}）
        </button>
      </div>

      <div v-for="(filter, index) in filters" :key="index" class="row wrap filter-row">
        <select v-model="filter.field" class="select tiny" @change="onFilterFieldChange(filter)">
          <option v-for="field in filterFields" :key="field.name" :value="field.name">{{ field.text }}</option>
        </select>
        <select v-model="filter.op" class="select tiny" @change="search">
          <template v-if="needsSelect(filter.field)">
            <option value="EQ">等于</option>
            <option value="NE">不等于</option>
            <option value="IS_NULL">为空</option>
            <option value="IS_NOT_NULL">不为空</option>
          </template>
          <template v-else-if="isFuzzy(filter.field)">
            <option value="LIKE">包含</option>
            <option value="EQ">等于</option>
            <option value="NE">不等于</option>
            <option value="GT">大于</option>
            <option value="LT">小于</option>
            <option value="GTE">不小于</option>
            <option value="LTE">不大于</option>
            <option value="IS_NULL">为空</option>
            <option value="IS_NOT_NULL">不为空</option>
          </template>
          <template v-else>
            <option value="EQ">等于</option>
            <option value="NE">不等于</option>
            <option value="GT">大于</option>
            <option value="LT">小于</option>
            <option value="GTE">不小于</option>
            <option value="LTE">不大于</option>
            <option value="IS_NULL">为空</option>
            <option value="IS_NOT_NULL">不为空</option>
          </template>
        </select>
        <template v-if="filter.op !== 'IS_NULL' && filter.op !== 'IS_NOT_NULL'">
          <select
            v-if="needsSelect(filter.field)"
            v-model="filter.value"
            class="select tiny wide"
            @change="search"
          >
            <option value="">全部</option>
            <option
              v-for="(label, value) in selectOptions(filter.field)"
              :key="value"
              :value="value"
            >{{ label }}</option>
          </select>
          <input
            v-else
            v-model="filter.value"
            class="input tiny"
            placeholder="值"
            @keyup.enter="search"
          />
        </template>
        <button class="btn ghost sm" @click="removeFilter(index)">移除</button>
      </div>
    </div>

    <div v-if="key === 'ticket' && (route.query.from || route.query.to || route.query.department)" class="row wrap">
      <span class="muted small">已应用工作台的日期与部门筛选</span>
      <router-link to="/m/ticket">清除报表筛选</router-link>
    </div>

    <div v-if="error" class="card error-card">{{ error }}</div>

    <div v-else class="card table-card">
      <div ref="tableWrap" class="table-wrap" @scroll="onTableScroll">
        <table class="data">
          <thead>
            <tr>
              <th v-if="canDelete" style="width: 38px">
                <input type="checkbox" :checked="allChecked" @change="toggleAll" />
              </th>
              <th
                v-for="field in visibleFields"
                :key="field.name"
                :class="{ sortable: field.sortable }"
                @click="sortBy(field)"
              >
                {{ field.text }}
                <span v-if="sort === field.name" class="muted">{{ order === 'asc' ? '↑' : '↓' }}</span>
              </th>
              <th v-if="canEdit || canDelete || supportsAttachments" style="width: 130px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td v-if="canDelete">
                <input v-model="selected" type="checkbox" :value="row.id" />
              </td>
              <td
                v-for="field in visibleFields"
                :key="field.name"
                :class="{ wrap: field.type === 'STRING' && field.maxLength > 120 }"
              >
                <span v-if="field.type === 'ENUM'" class="badge grey">{{ cell(row, field) }}</span>
                <img
                  v-else-if="field.type === 'IMAGE' && row[field.name]"
                  :src="row[field.name]"
                  class="cell-img"
                  alt=""
                />
                <span v-else-if="field.type === 'IMAGE'" class="muted">—</span>
                <span v-else>{{ cell(row, field) }}</span>
              </td>
              <td v-if="canEdit || canDelete || supportsAttachments">
                <button v-if="supportsAttachments" class="btn ghost sm" @click="openAttachments(row)">附件</button>
                <button v-if="canEdit" class="btn ghost sm" @click="openEdit(row)">编辑</button>
                <button v-if="canDelete" class="btn ghost sm danger-text" @click="remove(row)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="!rows.length" class="empty">{{ loading ? '加载中…' : '暂无数据' }}</div>

      <div class="pager">
        <span class="muted small">第 {{ page }} / {{ pages || 1 }} 页</span>
        <div class="grow"></div>
        <button class="btn sm" :disabled="page <= 1" @click="setPage(page - 1)">上一页</button>
        <button class="btn sm" :disabled="page >= pages" @click="setPage(page + 1)">下一页</button>
      </div>
    </div>

    <DataForm
      v-if="dialog.open"
      :schema="schema"
      :row="dialog.row"
      :mode="dialog.mode"
      :submitting="dialog.submitting"
      @submit="save"
      @cancel="dialog.open = false"
    />

    <div v-if="attachmentDialog" class="modal-mask" @mousedown.self="attachmentDialog = null">
      <div class="modal narrow" role="dialog" aria-label="业务附件">
        <header>
          <h3>业务附件</h3>
          <button class="btn ghost sm" @click="attachmentDialog = null">关闭</button>
        </header>
        <div class="body">
          <p v-if="attachmentDialog.loading" class="muted small">正在读取附件…</p>
          <p v-else-if="!attachmentDialog.files.length" class="muted small">此记录暂无附件。</p>
          <div v-for="file in attachmentDialog.files" :key="file.id" class="row wrap">
            <span class="grow">{{ file.name }}</span>
            <button class="btn ghost sm" @click="downloadAttachment(file)">下载</button>
            <button v-if="canEdit" class="btn ghost sm danger-text" :disabled="attachmentBusy"
                    @click="detachAttachment(file)">解除关联</button>
          </div>
        </div>
      </div>
    </div>

    <div v-if="columnDialog" class="modal-mask" @mousedown.self="columnDialog = false">
      <div class="modal narrow">
        <header>
          <h3>列设置</h3>
          <button class="btn ghost sm" @click="columnDialog = false">关闭</button>
        </header>
        <div class="body">
          <p class="muted small">勾选要显示的列，设置会保存在当前账号下。</p>
          <label v-for="field in tableFields" :key="field.name" class="check-line">
            <input v-model="pendingVisible" type="checkbox" :value="field.name" />
            <span>{{ field.text }}</span>
          </label>
          <div class="field" style="margin-top: 14px">
            <label>每页条数</label>
            <select v-model="pendingSize" class="select">
              <option :value="10">10</option>
              <option :value="20">20</option>
              <option :value="50">50</option>
              <option :value="100">100</option>
            </select>
          </div>
        </div>
        <footer>
          <button class="btn" @click="columnDialog = false">取消</button>
          <button class="btn primary" @click="saveColumns">保存</button>
        </footer>
      </div>
    </div>
  </div>

  <div v-else-if="error" class="card error-card">{{ error }}</div>
  <div v-else class="empty">加载中…</div>
</template>

<style scoped>
.head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.head h2 { font-size: 20px; }
.toolbar { padding: 12px 14px; margin-bottom: 14px; }
.wrap { flex-wrap: wrap; }
.search { width: 280px; }
.filter-row { margin-top: 10px; }
.tiny { height: 30px; width: auto; min-width: 110px; }
.select.tiny { min-width: 130px; }

/* 格式切换：与 34px 高的按钮同高，和「导出 / 下载导入模板」视觉上连成一组 */
.format-switch {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  height: 34px;
  padding: 0 4px 0 10px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--panel-2);
  user-select: none;
}
.format-label { margin-right: 4px; font-size: 12px; color: var(--muted); white-space: nowrap; }
.format-option {
  height: 26px;
  min-width: 56px;
  padding: 0 10px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--muted);
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: 0.15s ease;
  white-space: nowrap;
}
.format-option:hover:not(:disabled) { color: var(--text); }
.format-option.active {
  border-color: var(--border-strong);
  background: var(--panel);
  color: var(--primary);
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
}
.format-option:focus-visible { outline: 2px solid var(--primary); outline-offset: 1px; }
.format-option:disabled { opacity: 0.55; cursor: not-allowed; }
.table-card { overflow: hidden; }
.pager { display: flex; align-items: center; gap: 8px; padding: 12px 14px; border-top: 1px solid var(--border); }
.error-card { padding: 18px; color: var(--danger); background: var(--danger-weak); border-color: transparent; }
.danger-text { color: var(--danger); }
.cell-img { width: 36px; height: 36px; border-radius: 8px; object-fit: cover; border: 1px solid var(--border); }
.modal.narrow { max-width: 420px; }
.check-line { display: flex; align-items: center; gap: 8px; padding: 6px 0; }
</style>
