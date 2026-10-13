<template>
  <div class="article-list-page">
    <!-- 页头 -->
    <header class="page-header">
      <div class="header-inner">
        <div class="header-text">
          <h1 class="page-title">我的创作</h1>
          <p class="page-subtitle">管理你生成过的所有文章</p>
        </div>
        <a-button type="primary" size="large" @click="goToCreate">
          <template #icon><PlusOutlined /></template>
          创作新文章
        </a-button>
      </div>
    </header>

    <div class="page-body">
      <!-- 筛选栏 -->
      <div class="filter-bar">
        <a-input-search
          v-model:value="searchKeyword"
          placeholder="搜索标题或选题"
          allow-clear
          class="filter-item filter-search"
        >
          <template #prefix><SearchOutlined /></template>
        </a-input-search>

        <a-select
          v-model:value="statusFilter"
          class="filter-item filter-status"
          placeholder="全部状态"
          allow-clear
        >
          <a-select-option value="">全部状态</a-select-option>
          <a-select-option v-for="option in statusOptions" :key="option.value" :value="option.value">
            {{ option.label }}
          </a-select-option>
        </a-select>

        <a-range-picker
          v-model:value="dateRange"
          class="filter-item filter-date"
          :placeholder="['开始日期', '结束日期']"
        />

        <a-button v-if="hasLocalFilter" class="filter-item" @click="resetLocalFilter">
          <template #icon><ClearOutlined /></template>
          清除筛选
        </a-button>

        <span class="filter-summary">
          共 {{ pagination.total }} 篇文章
          <template v-if="hasLocalFilter">（本页筛出 {{ filteredRecords.length }} 篇）</template>
        </span>
      </div>

      <!-- 表格 -->
      <a-card :bordered="false" class="table-card">
        <a-table
          :columns="columns"
          :data-source="filteredRecords"
          :loading="loading"
          :pagination="pagination"
          row-key="id"
          @change="handleTableChange"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'topic'">
              <span class="topic-text">{{ record.topic || '-' }}</span>
            </template>

            <template v-else-if="column.key === 'title'">
              <button type="button" class="title-cell" @click="viewArticle(record)">
                <span class="main-title">{{ record.mainTitle || record.topic || '未命名文章' }}</span>
                <span v-if="record.subTitle" class="sub-title">{{ record.subTitle }}</span>
              </button>
            </template>

            <template v-else-if="column.key === 'status'">
              <span class="status-badge" :class="`is-${(record.status || 'unknown').toLowerCase()}`">
                <span class="status-dot" aria-hidden="true"></span>
                {{ getStatusText(record.status) }}
              </span>
            </template>

            <template v-else-if="column.key === 'createTime'">
              <span class="time-text">{{ formatDateTime(record.createTime) }}</span>
            </template>

            <template v-else-if="column.key === 'action'">
              <div class="action-group">
                <a-button type="link" size="small" @click="viewArticle(record)">
                  <template #icon><EyeOutlined /></template>
                  查看
                </a-button>

                <!-- 失败的文章没有正文，只能重新创作 -->
                <a-button
                  v-if="record.status === 'FAILED'"
                  type="link"
                  size="small"
                  @click="retryArticle(record)"
                >
                  <template #icon><RedoOutlined /></template>
                  重新创作
                </a-button>

                <a-button
                  v-else
                  type="link"
                  size="small"
                  :loading="exportingTaskId === record.taskId"
                  :disabled="!record.taskId"
                  @click="exportArticle(record)"
                >
                  <template #icon><DownloadOutlined /></template>
                  导出
                </a-button>

                <a-popconfirm
                  title="确定要删除这篇文章吗？"
                  ok-text="确定"
                  cancel-text="取消"
                  @confirm="handleDelete(record)"
                >
                  <a-button type="link" size="small" danger>
                    <template #icon><DeleteOutlined /></template>
                    删除
                  </a-button>
                </a-popconfirm>
              </div>
            </template>
          </template>

          <!-- 空状态 -->
          <template #emptyText>
            <div class="empty-state">
              <FileTextOutlined class="empty-icon" />
              <p class="empty-title">{{ hasLocalFilter ? '没有匹配的文章' : '还没有文章' }}</p>
              <p class="empty-desc">
                {{ hasLocalFilter ? '试试调整筛选条件' : '开始创作你的第一篇文章吧' }}
              </p>
              <a-button v-if="!hasLocalFilter" type="primary" @click="goToCreate">
                <template #icon><PlusOutlined /></template>
                创作新文章
              </a-button>
            </div>
          </template>
        </a-table>
      </a-card>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 文章列表页
 *
 * 分页与状态筛选走服务端（后端支持 pageNum / pageSize / status）；
 * 关键词与日期范围后端暂不支持，因此在**当前页**数据上做前端筛选，
 * 并在界面上标明筛选范围，避免"明明有却搜不到"的误解。
 *
 * @author ai-passage-creator
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  ClearOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EyeOutlined,
  FileTextOutlined,
  PlusOutlined,
  RedoOutlined,
  SearchOutlined,
} from '@ant-design/icons-vue'
import type { Dayjs } from 'dayjs'
import { deleteArticle as deleteArticleApi, getArticle, listArticle } from '@/api/articleController'
import {
  buildArticleMarkdown,
  downloadMarkdown,
  formatDateTime,
  getStatusText,
  parseDateTime,
} from './articleHelpers'

const router = useRouter()

/** 表格列定义 */
const columns = [
  { title: '选题', dataIndex: 'topic', key: 'topic', width: 200, ellipsis: true },
  { title: '标题', key: 'title', width: 300 },
  { title: '状态', key: 'status', width: 110 },
  { title: '创建时间', key: 'createTime', width: 160 },
  { title: '操作', key: 'action', width: 240 },
]

/** 状态下拉选项，与后端 ArticleStatusEnum 一致 */
const statusOptions = [
  { value: 'COMPLETED', label: '已完成' },
  { value: 'PROCESSING', label: '生成中' },
  { value: 'PENDING', label: '等待中' },
  { value: 'FAILED', label: '失败' },
]

const loading = ref(false)
const dataSource = ref<API.ArticleVO[]>([])
/** 正在导出的任务 ID，用于按钮级 loading */
const exportingTaskId = ref<string | null>(null)

/** 服务端筛选：状态 */
const statusFilter = ref<string>('')

/** 前端筛选：关键词、日期范围 */
const searchKeyword = ref('')
const dateRange = ref<[Dayjs, Dayjs] | undefined>(undefined)

const pagination = ref({
  current: 1,
  pageSize: 10,
  total: 0,
  showSizeChanger: true,
  showQuickJumper: true,
  pageSizeOptions: ['10', '20', '50'],
  showTotal: (total: number) => `共 ${total} 条`,
})

/** 是否存在前端筛选条件 */
const hasLocalFilter = computed(() => Boolean(searchKeyword.value.trim() || dateRange.value))

/**
 * 当前页数据的前端筛选结果
 *
 * 用 computed 而不是在请求后改写 dataSource：
 * 输入关键词时不需要重新请求，也不破坏服务端分页状态。
 */
const filteredRecords = computed(() => {
  const keyword = searchKeyword.value.trim().toLowerCase()
  const [start, end] = dateRange.value ?? []

  return dataSource.value.filter((record) => {
    if (keyword) {
      const haystack = [record.mainTitle, record.subTitle, record.topic]
        .filter(Boolean)
        .join(' ')
        .toLowerCase()
      if (!haystack.includes(keyword)) {
        return false
      }
    }

    if (start && end) {
      const created = parseDateTime(record.createTime)
      // 含首尾两天
      if (!created || created.isBefore(start.startOf('day')) || created.isAfter(end.endOf('day'))) {
        return false
      }
    }

    return true
  })
})

/** 加载列表数据 */
const loadData = async () => {
  loading.value = true
  try {
    const res = await listArticle({
      pageNum: pagination.value.current,
      pageSize: pagination.value.pageSize,
      status: statusFilter.value || undefined,
    })
    const pageData = res.data?.data
    dataSource.value = pageData?.records ?? []
    pagination.value.total = pageData?.totalRow ?? 0
  } catch (error: any) {
    message.error(error?.message || '加载失败')
    dataSource.value = []
    pagination.value.total = 0
  } finally {
    loading.value = false
  }
}

/** 切换状态筛选：回到第一页重新请求 */
watch(statusFilter, () => {
  pagination.value.current = 1
  void loadData()
})

/** 分页 / 排序变化 */
const handleTableChange = (pag: { current?: number; pageSize?: number }) => {
  pagination.value.current = pag.current ?? 1
  pagination.value.pageSize = pag.pageSize ?? 10
  void loadData()
}

/** 清除前端筛选条件 */
const resetLocalFilter = () => {
  searchKeyword.value = ''
  dateRange.value = undefined
}

/** 查看文章详情 */
const viewArticle = (record: API.ArticleVO) => {
  if (!record.taskId) {
    message.warning('任务 ID 不存在')
    return
  }
  router.push(`/article/${record.taskId}`)
}

/** 导出为 Markdown：列表里没有正文，需按 taskId 拉一次详情 */
const exportArticle = async (record: API.ArticleVO) => {
  if (!record.taskId) {
    message.warning('任务 ID 不存在')
    return
  }

  exportingTaskId.value = record.taskId
  try {
    const res = await getArticle({ taskId: record.taskId })
    const detail = res.data?.data
    if (!detail) {
      message.error('文章数据不存在')
      return
    }
    const markdown = buildArticleMarkdown(detail)
    if (!markdown) {
      message.warning('暂无可导出的内容')
      return
    }
    downloadMarkdown(markdown, detail.mainTitle || detail.topic || '文章')
    message.success('已导出 Markdown')
  } catch (error: any) {
    message.error(error?.message || '导出失败')
  } finally {
    exportingTaskId.value = null
  }
}

/** 删除文章 */
const handleDelete = async (record: API.ArticleVO) => {
  if (record.id == null) {
    message.warning('文章 ID 不存在')
    return
  }

  try {
    // DeleteRequest 是对象入参，不能直接传 id
    await deleteArticleApi({ id: record.id })
    message.success('删除成功')

    // 删掉的是当前页最后一条时回退一页，避免停在空页
    if (dataSource.value.length === 1 && pagination.value.current > 1) {
      pagination.value.current -= 1
    }
    await loadData()
  } catch (error: any) {
    message.error(error?.message || '删除失败')
  }
}

/** 失败的文章：带着原选题回到创作页重新生成 */
const retryArticle = (record: API.ArticleVO) => {
  router.push({ path: '/create', query: record.topic ? { topic: record.topic } : {} })
}

/** 跳转创作页 */
const goToCreate = () => {
  router.push('/create')
}

onMounted(() => {
  void loadData()
})
</script>

<style scoped lang="scss">
$primary: #667eea;
$primary-dark: #764ba2;

.article-list-page {
  min-height: 100vh;
  padding-bottom: 40px;
  background: #f0f2f5;
}

/* 页头 */
.page-header {
  padding: 32px 24px;
  background: linear-gradient(135deg, $primary 0%, $primary-dark 100%);
  color: #fff;

  .header-inner {
    display: flex;
    flex-wrap: wrap;
    gap: 16px;
    align-items: center;
    justify-content: space-between;
    max-width: 1400px;
    margin: 0 auto;
  }

  .page-title {
    margin: 0 0 6px;
    font-size: 26px;
    font-weight: 700;
  }

  .page-subtitle {
    margin: 0;
    font-size: 14px;
    opacity: 0.85;
  }
}

.page-body {
  max-width: 1400px;
  margin: 0 auto;
  padding: 20px 24px 0;
}

/* 筛选栏 */
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
  padding: 16px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);

  .filter-search {
    width: 280px;
    max-width: 100%;
  }

  .filter-status {
    width: 140px;
  }

  .filter-date {
    width: 260px;
    max-width: 100%;
  }

  .filter-summary {
    margin-left: auto;
    font-size: 13px;
    color: #8c8c8c;
  }
}

/* 表格卡片 */
.table-card {
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);

  :deep(.ant-card-body) {
    padding: 8px 16px 16px;
  }
}

.topic-text {
  color: #595959;
}

/* 标题单元格（整块可点，语义上是按钮） */
.title-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  padding: 4px;
  text-align: left;
  background: transparent;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.2s;

  &:hover {
    background: #f5f7ff;

    .main-title {
      color: $primary;
    }
  }

  &:focus-visible {
    outline: 2px solid $primary;
    outline-offset: 1px;
  }

  .main-title {
    font-size: 14px;
    font-weight: 600;
    color: #1a1a1a;
    transition: color 0.2s;
  }

  .sub-title {
    font-size: 12px;
    color: #999;
  }
}

/* 状态徽章 */
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  font-size: 12px;
  border-radius: 12px;
  background: #f5f5f5;
  color: #595959;

  .status-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: currentcolor;
  }

  &.is-completed {
    color: #389e0d;
    background: #f6ffed;
  }

  &.is-processing {
    color: #0958d9;
    background: #e6f4ff;
  }

  &.is-pending {
    color: #d46b08;
    background: #fff7e6;
  }

  &.is-failed {
    color: #cf1322;
    background: #fff1f0;
  }
}

.time-text {
  font-size: 13px;
  color: #8c8c8c;
}

.action-group {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px;
}

/* 空状态 */
.empty-state {
  padding: 48px 16px;
  text-align: center;

  .empty-icon {
    font-size: 44px;
    color: #d9d9d9;
  }

  .empty-title {
    margin: 12px 0 4px;
    font-size: 15px;
    font-weight: 600;
    color: #595959;
  }

  .empty-desc {
    margin: 0 0 16px;
    font-size: 13px;
    color: #bfbfbf;
  }
}

/* 响应式 */
@media (max-width: 768px) {
  .page-header {
    padding: 24px 16px;
  }

  .page-body {
    padding: 16px 12px 0;
  }

  .filter-bar {
    .filter-summary {
      width: 100%;
      margin-left: 0;
    }
  }
}
</style>
