<template>
  <div class="statistics-page">
    <!-- 页头 -->
    <header class="page-header">
      <div class="header-inner">
        <div class="header-text">
          <h1 class="page-title">数据统计</h1>
          <p class="page-subtitle">平台创作量与智能体运行情况（仅管理员可见）</p>
        </div>
        <a-button :loading="loading" @click="loadStatistics">
          <template #icon>
            <ReloadOutlined />
          </template>
          刷新
        </a-button>
      </div>
    </header>

    <div class="page-body">
      <a-spin :spinning="loading">
        <!-- 创作量 -->
        <section class="block">
          <h2 class="block-title">
            <BarChartOutlined />
            创作量
          </h2>
          <a-row :gutter="[16, 16]">
            <a-col v-for="item in countItems" :key="item.label" :xs="24" :sm="12" :lg="6">
              <a-card :bordered="false" class="kpi-card">
                <a-statistic :title="item.label" :value="item.value" suffix="篇" />
                <p class="kpi-hint">{{ item.hint }}</p>
              </a-card>
            </a-col>
          </a-row>
        </section>

        <!-- 运行质量 -->
        <section class="block">
          <h2 class="block-title">
            <ThunderboltOutlined />
            运行质量
          </h2>
          <a-row :gutter="[16, 16]">
            <a-col :xs="24" :md="8">
              <a-card :bordered="false" class="metric-card rate-card">
                <div class="metric-label">创作成功率</div>
                <a-progress
                  type="dashboard"
                  :percent="successRatePercent"
                  :width="132"
                  :stroke-color="rateStrokeColor"
                />
                <p class="metric-hint">
                  {{ succeededCount }} / {{ totalArticleCount }} 篇创作任务成功完成
                </p>
              </a-card>
            </a-col>

            <a-col :xs="24" :md="8">
              <a-card :bordered="false" class="metric-card">
                <div class="metric-label">平均 AI 处理耗时</div>
                <div class="metric-value">
                  {{ avgDurationText }}
                </div>
                <p class="metric-hint">各智能体执行耗时之和，不含人工确认的等待时间</p>
              </a-card>
            </a-col>

            <a-col :xs="24" :md="8">
              <a-card :bordered="false" class="metric-card">
                <div class="metric-label">配额消耗总量</div>
                <div class="metric-value">{{ stats.quotaUsed ?? 0 }}<span class="unit">次</span></div>
                <p class="metric-hint">所有用户累计消耗的创作配额</p>
              </a-card>
            </a-col>
          </a-row>
        </section>

        <!-- 用户 -->
        <section class="block">
          <h2 class="block-title">
            <TeamOutlined />
            用户
          </h2>
          <a-row :gutter="[16, 16]">
            <a-col v-for="item in userItems" :key="item.label" :xs="24" :sm="8">
              <a-card :bordered="false" class="kpi-card">
                <a-statistic :title="item.label" :value="item.value" suffix="人" />
                <p class="kpi-hint">{{ item.hint }}</p>
              </a-card>
            </a-col>
          </a-row>
        </section>

        <!-- VIP 占比 -->
        <section class="block">
          <a-card :bordered="false" class="vip-card">
            <div class="vip-header">
              <span class="vip-title">
                <CrownOutlined />
                VIP 用户占比
              </span>
              <span class="vip-ratio">{{ vipRatioPercent }}%</span>
            </div>
            <a-progress
              :percent="vipRatioPercent"
              :stroke-color="{ from: '#c89a5e', to: '#a6763a' }"
              :show-info="false"
            />
            <p class="vip-hint">{{ stats.vipUserCount ?? 0 }} 位永久会员 / 共 {{ stats.totalUserCount ?? 0 }} 位用户</p>
          </a-card>
        </section>
      </a-spin>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 数据统计（管理后台）
 *
 * 数据来自 GET /statistics/overview，仅管理员可访问；路由本身也在 access.ts 的 /admin 守卫内。
 * 只有聚合值、没有时间序列，所以用 KPI 卡片 + 环形进度呈现，不强行画趋势图。
 */
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  BarChartOutlined,
  CrownOutlined,
  ReloadOutlined,
  TeamOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons-vue'
import { getStatistics } from '@/api/statisticsController'

const loading = ref(false)
const stats = ref<API.StatisticsVO>({})

/** 创作量四项 */
const countItems = computed(() => [
  { label: '今日创作', value: stats.value.todayCount ?? 0, hint: '今天新发起的创作任务' },
  { label: '本周创作', value: stats.value.weekCount ?? 0, hint: '本周累计创作任务' },
  { label: '本月创作', value: stats.value.monthCount ?? 0, hint: '本月累计创作任务' },
  { label: '累计创作', value: stats.value.totalCount ?? 0, hint: '平台上线以来的总任务数' },
])

/** 用户三项 */
const userItems = computed(() => [
  { label: '本周活跃用户', value: stats.value.activeUserCount ?? 0, hint: '本周有过创作行为的用户' },
  { label: '用户总数', value: stats.value.totalUserCount ?? 0, hint: '平台注册用户总量' },
  { label: 'VIP 用户', value: stats.value.vipUserCount ?? 0, hint: '已开通永久会员的用户' },
])

/** 总创作数（成功率分母展示用） */
const totalArticleCount = computed(() => stats.value.totalCount ?? 0)

/** 成功任务数：由总任务数与成功率反推，仅用于文案展示 */
const succeededCount = computed(() => {
  const rate = successRatePercent.value
  return Math.round((totalArticleCount.value * rate) / 100)
})

/** 成功率百分比：后端给的是 0-100 的百分比数值 */
const successRatePercent = computed(() => {
  const rate = stats.value.successRate
  if (typeof rate !== 'number' || Number.isNaN(rate)) {
    return 0
  }
  return Math.max(0, Math.min(100, Math.round(rate * 10) / 10))
})

/** 成功率环形配色：低分红色、中等橙色、高分绿色 */
const rateStrokeColor = computed(() => {
  const rate = successRatePercent.value
  if (rate >= 90) {
    return 'var(--c-ok)'
  }
  if (rate >= 70) {
    return '#faad14'
  }
  return 'var(--c-danger)'
})

/** 平均耗时：毫秒转可读文本 */
const avgDurationText = computed(() => {
  const ms = stats.value.avgDurationMs
  if (typeof ms !== 'number' || ms <= 0) {
    return '-'
  }
  if (ms < 1000) {
    return `${Math.round(ms)} ms`
  }
  const seconds = ms / 1000
  if (seconds < 60) {
    return `${seconds.toFixed(1)} 秒`
  }
  const minutes = Math.floor(seconds / 60)
  const rest = Math.round(seconds % 60)
  return `${minutes} 分 ${rest} 秒`
})

/** VIP 占比 */
const vipRatioPercent = computed(() => {
  const total = stats.value.totalUserCount ?? 0
  const vip = stats.value.vipUserCount ?? 0
  if (total <= 0) {
    return 0
  }
  return Math.max(0, Math.min(100, Math.round((vip / total) * 1000) / 10))
})

const loadStatistics = async () => {
  loading.value = true
  try {
    const res = await getStatistics()
    stats.value = res.data?.data ?? {}
  } catch (error: any) {
    message.error(error?.message || '加载统计数据失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadStatistics()
})
</script>

<style scoped lang="scss">
$primary: var(--c-primary);
$gold: var(--c-accent);

.statistics-page {
  min-height: 100vh;
  padding-bottom: 40px;
  background: var(--c-bg);
}

/* 页头 */
.page-header {
  padding: 28px 24px;
  background: var(--c-surface);
  color: var(--c-text-1);
  border-bottom: 1px solid var(--c-border);

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
    font-size: 24px;
    font-weight: 700;
  }

  .page-subtitle {
    margin: 0;
    font-size: 13px;
    opacity: 0.85;
  }
}

.page-body {
  max-width: 1400px;
  margin: 0 auto;
  padding: 20px 24px 0;
}

/* 分区 */
.block {
  margin-bottom: 24px;

  .block-title {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 0 0 14px;
    font-size: 16px;
    font-weight: 600;
    color: var(--c-text-1);
  }
}

/* KPI 卡 */
.kpi-card {
  height: 100%;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(26, 26, 25, 0.06);

  :deep(.ant-statistic-title) {
    font-size: 13px;
    color: var(--c-text-2);
  }

  :deep(.ant-statistic-content) {
    font-size: 30px;
    font-weight: 700;
    color: var(--c-text-1);
  }

  :deep(.ant-statistic-content-suffix) {
    margin-left: 4px;
    font-size: 13px;
    font-weight: 400;
    color: var(--c-text-2);
  }

  .kpi-hint {
    margin: 8px 0 0;
    font-size: 12px;
    color: var(--c-text-3);
  }
}

/* 指标卡 */
.metric-card {
  height: 100%;
  border-radius: 12px;
  text-align: center;
  box-shadow: 0 2px 8px rgba(26, 26, 25, 0.06);

  .metric-label {
    margin-bottom: 12px;
    font-size: 13px;
    color: var(--c-text-2);
  }

  .metric-value {
    font-size: 32px;
    font-weight: 700;
    line-height: 1.3;
    color: var(--c-text-1);

    .unit {
      margin-left: 4px;
      font-size: 14px;
      font-weight: 400;
      color: var(--c-text-2);
    }
  }

  .metric-hint {
    margin: 10px 0 0;
    font-size: 12px;
    line-height: 1.6;
    color: var(--c-text-3);
  }

  &.rate-card {
    padding-bottom: 8px;
  }
}

/* VIP 占比 */
.vip-card {
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(26, 26, 25, 0.06);

  .vip-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12px;
  }

  .vip-title {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 14px;
    font-weight: 600;
    color: var(--c-text-1);
  }

  .vip-ratio {
    font-size: 22px;
    font-weight: 700;
    color: $gold;
  }

  .vip-hint {
    margin: 10px 0 0;
    font-size: 12px;
    color: var(--c-text-3);
  }
}

/* 响应式 */
@media (max-width: 768px) {
  .page-header {
    padding: 20px 16px;
  }

  .page-body {
    padding: 16px 12px 0;
  }
}
</style>
