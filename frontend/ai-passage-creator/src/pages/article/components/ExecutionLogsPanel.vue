<template>
  <div class="execution-logs-panel">
    <a-spin :spinning="loading">
      <template v-if="stats">
        <!-- 汇总 -->
        <div class="log-summary">
          <div class="summary-item">
            <span class="summary-label">总体状态</span>
            <a-tag :color="overallStatusColor">{{ overallStatusText }}</a-tag>
          </div>
          <div class="summary-item">
            <span class="summary-label">智能体数量</span>
            <span class="summary-value">{{ stats.agentCount ?? logs.length }}</span>
          </div>
          <div class="summary-item">
            <span class="summary-label">总耗时</span>
            <span class="summary-value">{{ formatDuration(stats.totalDurationMs) }}</span>
          </div>
        </div>

        <!-- 各智能体执行明细 -->
        <a-collapse v-if="logs.length" ghost class="agent-collapse">
          <a-collapse-panel
            v-for="(log, index) in logs"
            :key="log.id ?? `${log.agentName ?? 'agent'}-${index}`"
          >
            <template #header>
              <div class="log-header">
                <span class="agent-name">{{ getAgentText(log.agentName) }}</span>
                <a-tag :color="getStatusColor(log.status)">{{ getStatusText(log.status) }}</a-tag>
                <span class="duration">{{ formatDuration(log.durationMs) }}</span>
              </div>
            </template>

            <div class="log-detail">
              <div class="detail-row">
                <span class="detail-label">开始时间</span>
                <span>{{ formatDateTime(log.startTime) }}</span>
              </div>
              <div class="detail-row">
                <span class="detail-label">结束时间</span>
                <span>{{ formatDateTime(log.endTime) }}</span>
              </div>

              <div v-if="log.errorMessage" class="detail-error">
                <WarningOutlined />
                <span>{{ log.errorMessage }}</span>
              </div>

              <!--
                原生 details 折叠：LLM 的 prompt / 输入输出往往上千字，
                默认收起避免刷屏，需要排查时再逐项展开
              -->
              <details v-if="log.prompt" class="code-details">
                <summary>Prompt</summary>
                <pre class="code-block">{{ truncate(log.prompt) }}</pre>
              </details>
              <details v-if="log.inputData" class="code-details">
                <summary>输入数据</summary>
                <pre class="code-block">{{ truncate(log.inputData) }}</pre>
              </details>
              <details v-if="log.outputData" class="code-details">
                <summary>输出数据</summary>
                <pre class="code-block">{{ truncate(log.outputData) }}</pre>
              </details>
            </div>
          </a-collapse-panel>
        </a-collapse>

        <a-empty v-else description="暂无执行日志" />
      </template>

      <a-empty v-else-if="!loading" :description="errorMessage || '暂无执行日志'" />
    </a-spin>
  </div>
</template>

<script setup lang="ts">
/**
 * 智能体执行日志面板
 *
 * 数据来自 GET /article/execution-logs/{taskId}，用于排查某篇文章在哪个智能体环节出了什么问题。
 * 组件自行加载数据，父页面只需传入 taskId；加载失败不抛出、只在面板内提示，避免影响详情页主体阅读。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { WarningOutlined } from '@ant-design/icons-vue'
import dayjs from 'dayjs'
import { getExecutionLogs } from '@/api/articleController'

interface Props {
  /** 文章任务 ID */
  taskId: string
}

const props = defineProps<Props>()

const loading = ref(false)
const errorMessage = ref('')
const stats = ref<API.AgentExecutionStats | null>(null)

const logs = computed(() => stats.value?.logs ?? [])

/** 智能体标识 → 中文名，与后端 @AgentExecution(value) 一一对应 */
const AGENT_NAME_TEXT: Record<string, string> = {
  agent_generate_title_options: '生成标题方案',
  agent_generate_outline: '生成大纲',
  agent_generate_content: '生成正文',
  agent_analyze_image_requirements: '分析配图需求',
  agent_generate_images: '生成配图',
  ai_modify_outline: 'AI 修改大纲',
  merge_images_into_content: '图文合成',
}

const getAgentText = (name?: string): string => (name ? AGENT_NAME_TEXT[name] ?? name : '未知智能体')

const STATUS_TEXT: Record<string, string> = {
  SUCCESS: '成功',
  FAILED: '失败',
  RUNNING: '执行中',
}

const STATUS_COLOR: Record<string, string> = {
  SUCCESS: 'success',
  FAILED: 'error',
  RUNNING: 'processing',
}

const getStatusText = (status?: string): string => (status ? STATUS_TEXT[status] ?? status : '未知')
const getStatusColor = (status?: string): string =>
  status ? STATUS_COLOR[status] ?? 'default' : 'default'

/** 总体状态映射 */
const overallStatusText = computed(() => {
  const status = stats.value?.overallStatus
  if (status === 'SUCCESS') {
    return '全部成功'
  }
  if (status === 'FAILED') {
    return '存在失败环节'
  }
  if (status === 'RUNNING') {
    return '执行中'
  }
  return status ?? '未知'
})

const overallStatusColor = computed(() => {
  const status = stats.value?.overallStatus
  if (status === 'SUCCESS') {
    return 'success'
  }
  if (status === 'FAILED') {
    return 'error'
  }
  return 'processing'
})

/** 耗时格式化 */
const formatDuration = (ms?: number): string => {
  if (typeof ms !== 'number' || ms < 0) {
    return '-'
  }
  if (ms < 1000) {
    return `${Math.round(ms)} ms`
  }
  const seconds = ms / 1000
  if (seconds < 60) {
    return `${seconds.toFixed(1)} 秒`
  }
  return `${Math.floor(seconds / 60)} 分 ${Math.round(seconds % 60)} 秒`
}

const formatDateTime = (value?: string): string => {
  if (!value) {
    return '-'
  }
  const parsed = dayjs(value)
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : '-'
}

/** 截断超长内容，避免一次渲染几万字 */
const truncate = (text?: string, max = 3000): string => {
  if (!text) {
    return ''
  }
  return text.length > max ? `${text.slice(0, max)}\n……（已截断，共 ${text.length} 字）` : text
}

const loadLogs = async () => {
  if (!props.taskId) {
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    const res = await getExecutionLogs({ taskId: props.taskId })
    stats.value = res.data?.data ?? null
  } catch (error: any) {
    stats.value = null
    errorMessage.value = error?.message || '加载执行日志失败'
  } finally {
    loading.value = false
  }
}

watch(
  () => props.taskId,
  () => {
    void loadLogs()
  },
)

onMounted(() => {
  void loadLogs()
})
</script>

<style scoped lang="scss">
.execution-logs-panel {
  min-height: 80px;
}

/* 汇总条 */
.log-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  padding: 12px 16px;
  margin-bottom: 12px;
  background: #fafafa;
  border-radius: 8px;

  .summary-item {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .summary-label {
    font-size: 13px;
    color: #8c8c8c;
  }

  .summary-value {
    font-size: 14px;
    font-weight: 600;
    color: #1a1a1a;
  }
}

/* 折叠面板 */
.agent-collapse {
  :deep(.ant-collapse-item) {
    margin-bottom: 8px;
    overflow: hidden;
    background: #fafafa;
    border: 1px solid #f0f0f0;
    border-radius: 8px !important;
  }

  :deep(.ant-collapse-header) {
    padding: 10px 14px !important;
    align-items: center !important;
  }

  :deep(.ant-collapse-content-box) {
    padding: 0 14px 14px !important;
  }
}

.log-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;

  .agent-name {
    font-size: 14px;
    font-weight: 600;
    color: #1a1a1a;
  }

  .duration {
    font-size: 12px;
    color: #8c8c8c;
  }
}

/* 明细 */
.log-detail {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.detail-row {
  display: flex;
  gap: 12px;
  font-size: 13px;
  color: #595959;

  .detail-label {
    flex: 0 0 72px;
    color: #8c8c8c;
  }
}

.detail-error {
  display: flex;
  gap: 8px;
  padding: 8px 12px;
  font-size: 13px;
  line-height: 1.6;
  color: #cf1322;
  background: #fff1f0;
  border: 1px solid #ffccc7;
  border-radius: 6px;
}

/* 代码块折叠 */
.code-details {
  margin-top: 4px;

  summary {
    font-size: 13px;
    color: #667eea;
    cursor: pointer;
    user-select: none;

    &:hover {
      text-decoration: underline;
    }
  }
}

.code-block {
  max-height: 320px;
  margin: 8px 0 0;
  padding: 12px;
  overflow: auto;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.6;
  color: #333;
  white-space: pre-wrap;
  word-break: break-word;
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 6px;
}

@media (max-width: 768px) {
  .log-summary {
    gap: 12px;
  }
}
</style>
