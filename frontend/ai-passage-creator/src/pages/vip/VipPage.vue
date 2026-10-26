<template>
  <div class="vip-page">
    <!-- 顶部横幅 -->
    <section class="vip-hero">
      <div class="hero-content">
        <div class="hero-badge">
          <CrownOutlined />
          <span>会员中心</span>
        </div>
        <h1 class="hero-title">{{ isVip ? '您已是永久会员' : '开通会员，解锁全部能力' }}</h1>
        <p class="hero-subtitle">
          {{ isVip ? '感谢支持，所有高级功能已为您解锁' : '一次付费，永久有效，无需续费' }}
        </p>
      </div>
    </section>

    <div class="vip-body">
      <!-- 未登录 -->
      <a-card v-if="!isLoggedIn" :bordered="false" class="login-card">
        <a-empty description="登录后查看会员信息">
          <a-button type="primary" @click="goLogin">去登录</a-button>
        </a-empty>
      </a-card>

      <template v-else>
        <!-- 未开通：价格与权益 -->
        <a-card v-if="!isVip" :bordered="false" class="pricing-card">
          <div class="price-header">
            <span class="price-currency">$</span>
            <span class="price-amount">199</span>
            <span class="price-unit">/ 永久</span>
          </div>
          <p class="price-desc">一次性付费，终身有效，无需续费</p>

          <ul class="benefit-list">
            <li v-for="item in BENEFITS" :key="item.title">
              <CheckCircleFilled class="benefit-icon" />
              <div class="benefit-text">
                <div class="benefit-title">{{ item.title }}</div>
                <div class="benefit-desc">{{ item.desc }}</div>
              </div>
            </li>
          </ul>

          <a-button
            type="primary"
            size="large"
            block
            :loading="paying"
            class="buy-btn"
            @click="handleSubscribe"
          >
            <template #icon>
              <CrownOutlined />
            </template>
            立即开通永久会员
          </a-button>
          <p class="pay-hint">将跳转至 Stripe 安全支付页面，支持信用卡支付</p>
        </a-card>

        <!-- 已开通：会员状态 -->
        <a-card v-else :bordered="false" class="active-card">
          <div class="active-header">
            <CrownOutlined class="active-icon" />
            <div class="active-info">
              <h2>永久会员</h2>
              <p>开通时间：{{ formatDateTime(loginUserStore.loginUser.vipTime) }}</p>
            </div>
            <a-tag color="gold">已激活</a-tag>
          </div>

          <a-divider />

          <div class="active-actions">
            <a-button :loading="refreshing" @click="refreshUser">
              <template #icon>
                <ReloadOutlined />
              </template>
              刷新会员状态
            </a-button>
            <a-button danger :loading="loadingRecords" @click="refundModalVisible = true">
              申请退款
            </a-button>
          </div>
          <p class="refund-hint">退款后会员权益立即失效，创作配额恢复为默认的 5 次</p>
        </a-card>

        <!-- 支付记录 -->
        <a-card :bordered="false" class="records-card">
          <template #title>支付记录</template>
          <template #extra>
            <a-button size="small" :loading="loadingRecords" @click="loadRecords">
              <template #icon>
                <ReloadOutlined />
              </template>
              刷新
            </a-button>
          </template>

          <a-table
            :columns="columns"
            :data-source="records"
            :loading="loadingRecords"
            :pagination="false"
            row-key="id"
            size="middle"
            :scroll="{ x: 720 }"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'amount'">
                <span class="amount">{{ formatAmount(record.amount, record.currency) }}</span>
              </template>

              <template v-else-if="column.key === 'status'">
                <a-tag :color="getStatusColor(record.status)">
                  {{ getStatusText(record.status) }}
                </a-tag>
              </template>

              <template v-else-if="column.key === 'createTime'">
                {{ formatDateTime(record.createTime) }}
              </template>

              <template v-else-if="column.key === 'refundInfo'">
                <span v-if="record.refundTime" class="refund-info">
                  {{ formatDateTime(record.refundTime) }}
                  <template v-if="record.refundReason">（{{ record.refundReason }}）</template>
                </span>
                <span v-else class="muted">-</span>
              </template>
            </template>

            <template #emptyText>
              <a-empty description="暂无支付记录" />
            </template>
          </a-table>
        </a-card>
      </template>
    </div>

    <!-- 退款确认 -->
    <a-modal
      v-model:open="refundModalVisible"
      title="申请退款"
      :confirm-loading="refunding"
      ok-text="确认退款"
      cancel-text="取消"
      @ok="handleRefund"
    >
      <a-alert
        class="refund-alert"
        type="warning"
        show-icon
        message="退款后会员权益立即失效"
        description="创作配额会恢复为默认的 5 次，已生成的文章与图片会保留。"
      />
      <a-textarea
        v-model:value="refundReason"
        class="reason-input"
        placeholder="请填写退款原因（可选）"
        :rows="3"
        :maxlength="200"
        show-count
      />
    </a-modal>
  </div>
</template>

<script setup lang="ts">
/**
 * 会员中心
 *
 * 三块内容：会员状态、开通入口（跳 Stripe 托管收银台）、支付记录与退款。
 * 支付结果由后端 Stripe Webhook 权威判定，前端只负责发起支付与回跳后刷新用户信息，
 * 不依据前端状态推断「已开通」。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import { CheckCircleFilled, CrownOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { createVipPaymentSession, getPaymentRecords, refund } from '@/api/paymentController'
import { useLoginUserStore } from '@/stores/loginUser'

const route = useRoute()
const router = useRouter()
const loginUserStore = useLoginUserStore()

const paying = ref(false)
const refreshing = ref(false)
const refunding = ref(false)
const loadingRecords = ref(false)
const records = ref<API.PaymentRecord[]>([])
const refundModalVisible = ref(false)
const refundReason = ref('')

/** 是否已登录 */
const isLoggedIn = computed(() => Boolean(loginUserStore.loginUser.id))
/** 是否已是会员（后端用 userRole = vip 表示永久会员） */
const isVip = computed(() => loginUserStore.loginUser.userRole === 'vip')

/** 会员权益，与后端实际能力对应 */
const BENEFITS = [
  { title: '无限创作配额', desc: '不受默认 5 次限制，想写多少篇都可以' },
  { title: '解锁全部配图方式', desc: 'Unsplash、AI 生图、Mermaid 流程图、Iconify 图标、表情包、SVG 示意图' },
  { title: '三阶段人机协作', desc: '自选标题方案、拖拽编辑大纲、AI 辅助改写大纲' },
  { title: '永久有效', desc: '一次付费，终身有效，后续新功能同样可用' },
]

const columns = [
  { title: '订单号', dataIndex: 'stripeSessionId', key: 'stripeSessionId', width: 220, ellipsis: true },
  { title: '金额', key: 'amount', width: 110 },
  { title: '状态', key: 'status', width: 110 },
  { title: '产品', dataIndex: 'description', key: 'description', width: 140, ellipsis: true },
  { title: '创建时间', key: 'createTime', width: 160 },
  { title: '退款信息', key: 'refundInfo', width: 220, ellipsis: true },
]

const PAYMENT_STATUS_TEXT: Record<string, string> = {
  PENDING: '待支付',
  SUCCEEDED: '支付成功',
  FAILED: '支付失败',
  REFUNDED: '已退款',
}

const PAYMENT_STATUS_COLOR: Record<string, string> = {
  PENDING: 'default',
  SUCCEEDED: 'success',
  FAILED: 'error',
  REFUNDED: 'warning',
}

const getStatusText = (status?: string): string => (status ? PAYMENT_STATUS_TEXT[status] ?? status : '-')
const getStatusColor = (status?: string): string =>
  status ? PAYMENT_STATUS_COLOR[status] ?? 'default' : 'default'

/** 时间格式化（后端 LocalDateTime 序列化为 ISO 字符串） */
const formatDateTime = (value?: string): string => {
  if (!value) {
    return '-'
  }
  const parsed = dayjs(value)
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm') : '-'
}

/** 金额展示：后端以美元计价，amount 为带两位小数的数值 */
const formatAmount = (amount?: number, currency?: string): string => {
  if (typeof amount !== 'number') {
    return '-'
  }
  const symbol = (currency ?? 'usd').toUpperCase() === 'USD' ? '$' : `${(currency ?? '').toUpperCase()} `
  return `${symbol}${amount.toFixed(2)}`
}

const goLogin = () => {
  void router.push({ path: '/user/login', query: { redirect: '/vip' } })
}

/** 刷新登录用户信息（获取最新会员状态） */
const refreshUser = async () => {
  refreshing.value = true
  try {
    await loginUserStore.fetchLoginUser()
    message.success(isVip.value ? '会员状态已是最新' : '已刷新')
  } catch (error: any) {
    message.error(error?.message || '刷新失败，请稍后重试')
  } finally {
    refreshing.value = false
  }
}

/** 加载支付记录 */
const loadRecords = async () => {
  loadingRecords.value = true
  try {
    const res = await getPaymentRecords()
    records.value = res.data?.data ?? []
  } catch (error: any) {
    records.value = []
    message.error(error?.message || '加载支付记录失败')
  } finally {
    loadingRecords.value = false
  }
}

/** 开通会员：创建 Stripe 收银台会话后整页跳转 */
const handleSubscribe = async () => {
  paying.value = true
  try {
    const res = await createVipPaymentSession()
    const sessionUrl = res.data?.data
    if (!sessionUrl) {
      throw new Error(res.data?.message || '创建支付会话失败')
    }
    // 交给 Stripe 托管页面完成支付，支付结果由后端 Webhook 更新
    window.location.href = sessionUrl
  } catch (error: any) {
    message.error(error?.message || '创建支付会话失败')
    paying.value = false
  }
}

/** 申请退款 */
const handleRefund = async () => {
  refunding.value = true
  try {
    const reason = refundReason.value.trim()
    const res = await refund(reason ? { reason } : {})

    if (res.data?.data) {
      message.success('退款成功，会员权益已撤销')
      refundModalVisible.value = false
      refundReason.value = ''
      await Promise.all([refreshUser(), loadRecords()])
    } else {
      message.error(res.data?.message || '退款失败，请联系客服')
    }
  } catch (error: any) {
    message.error(error?.message || '退款失败，请稍后重试')
  } finally {
    refunding.value = false
  }
}

onMounted(async () => {
  if (!isLoggedIn.value) {
    return
  }

  // Stripe 支付完成后回跳（后端 success-url 配置带 success=1 时生效）
  const { success } = route.query
  const paid = success === '1' || success === 'true'
  if (paid) {
    // Webhook 可能略有延迟，先刷新一次再判断
    await loginUserStore.fetchLoginUser()
    if (isVip.value) {
      message.success('开通成功，欢迎成为永久会员！')
    } else {
      message.warning('支付结果确认中，请稍后点击「刷新会员状态」')
    }
    // 清掉 query，避免刷新页面重复提示
    await router.replace({ path: '/vip' })
  }

  await loadRecords()
})
</script>

<style scoped lang="scss">
$gold: #d4a017;
$gold-light: #faad14;

.vip-page {
  min-height: 100vh;
  background: #f0f2f5;
}

/* 顶部横幅 */
.vip-hero {
  padding: 48px 24px 56px;
  background: linear-gradient(135deg, #2b2118 0%, #4a3a24 55%, #7a5c2e 100%);
  color: #fff;

  .hero-content {
    max-width: 880px;
    margin: 0 auto;
    text-align: center;
  }

  .hero-badge {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 6px 16px;
    margin-bottom: 18px;
    font-size: 13px;
    color: #ffe7a3;
    background: rgba(255, 255, 255, 0.1);
    border: 1px solid rgba(255, 231, 163, 0.35);
    border-radius: 999px;
  }

  .hero-title {
    margin: 0 0 12px;
    font-size: 32px;
    font-weight: 700;
    letter-spacing: 0.5px;
  }

  .hero-subtitle {
    margin: 0;
    font-size: 15px;
    color: rgba(255, 255, 255, 0.75);
  }
}

.vip-body {
  max-width: 880px;
  margin: -28px auto 0;
  padding: 0 24px 48px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* 通用卡片 */
.login-card,
.pricing-card,
.active-card,
.records-card {
  border-radius: 14px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.08);
}

/* 价格卡 */
.pricing-card {
  padding: 8px;

  .price-header {
    display: flex;
    align-items: baseline;
    justify-content: center;
    gap: 4px;
    color: $gold;
  }

  .price-currency {
    font-size: 24px;
    font-weight: 600;
  }

  .price-amount {
    font-size: 56px;
    font-weight: 800;
    line-height: 1.1;
  }

  .price-unit {
    font-size: 15px;
    color: #8c8c8c;
  }

  .price-desc {
    margin: 6px 0 28px;
    font-size: 14px;
    color: #8c8c8c;
    text-align: center;
  }

  .benefit-list {
    display: flex;
    flex-direction: column;
    gap: 16px;
    margin: 0 0 28px;
    padding: 0;
    list-style: none;

    li {
      display: flex;
      gap: 12px;
      align-items: flex-start;
    }

    .benefit-icon {
      flex: 0 0 auto;
      margin-top: 3px;
      font-size: 18px;
      color: #52c41a;
    }

    .benefit-title {
      font-size: 15px;
      font-weight: 600;
      color: #1a1a1a;
    }

    .benefit-desc {
      margin-top: 2px;
      font-size: 13px;
      line-height: 1.6;
      color: #8c8c8c;
    }
  }

  .buy-btn {
    height: 52px;
    font-size: 17px;
    font-weight: 600;
    border: none;
    border-radius: 10px;
    background: linear-gradient(135deg, $gold-light 0%, $gold 100%);

    &:not(:disabled):hover {
      transform: translateY(-2px);
      box-shadow: 0 8px 20px rgba(212, 160, 23, 0.4);
    }
  }

  .pay-hint {
    margin: 12px 0 0;
    font-size: 12px;
    color: #bfbfbf;
    text-align: center;
  }
}

/* 已开通状态卡 */
.active-card {
  .active-header {
    display: flex;
    align-items: center;
    gap: 16px;
  }

  .active-icon {
    font-size: 40px;
    color: $gold;
  }

  .active-info {
    flex: 1;

    h2 {
      margin: 0 0 4px;
      font-size: 20px;
      font-weight: 700;
      color: #1a1a1a;
    }

    p {
      margin: 0;
      font-size: 13px;
      color: #8c8c8c;
    }
  }

  .active-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
  }

  .refund-hint {
    margin: 12px 0 0;
    font-size: 12px;
    color: #bfbfbf;
  }
}

/* 支付记录 */
.records-card {
  .amount {
    font-weight: 600;
    color: $gold;
  }

  .refund-info {
    font-size: 13px;
    color: #8c8c8c;
  }

  .muted {
    color: #bfbfbf;
  }
}

/* 退款弹窗 */
.refund-alert {
  margin-bottom: 16px;
}

.reason-input {
  border-radius: 8px;
}

/* 响应式 */
@media (max-width: 768px) {
  .vip-hero {
    padding: 32px 16px 44px;

    .hero-title {
      font-size: 24px;
    }
  }

  .vip-body {
    padding: 0 12px 32px;
  }

  .pricing-card .price-amount {
    font-size: 44px;
  }
}
</style>
