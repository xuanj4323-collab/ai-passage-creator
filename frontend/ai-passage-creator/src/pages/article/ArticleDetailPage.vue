<template>
  <div class="article-detail-page">
    <!-- 顶部操作栏 -->
    <div class="detail-toolbar">
      <a-button @click="goBack">
        <template #icon><ArrowLeftOutlined /></template>
        返回
      </a-button>

      <a-space :size="12">
        <a-button :disabled="!article" @click="copyContent">
          <template #icon><CopyOutlined /></template>
          复制全文
        </a-button>
        <a-button type="primary" :loading="exporting" :disabled="!article" @click="exportMarkdown">
          <template #icon><DownloadOutlined /></template>
          导出 Markdown
        </a-button>
      </a-space>
    </div>

    <a-skeleton v-if="loading" active :paragraph="{ rows: 10 }" class="detail-skeleton" />

    <article v-else-if="article" class="detail-card">
      <!-- 标题区 -->
      <header class="title-section">
        <h1 class="main-title">{{ article.mainTitle || article.topic || '未命名文章' }}</h1>
        <p v-if="article.subTitle" class="sub-title">{{ article.subTitle }}</p>

        <div class="meta-info">
          <a-tag :color="getStatusColor(article.status)">{{ getStatusText(article.status) }}</a-tag>
          <span v-if="article.createTime" class="meta-item">
            创建于
            <time :datetime="String(article.createTime)">{{ formatDateTime(article.createTime) }}</time>
          </span>
          <span v-if="article.completedTime" class="meta-item">
            完成于 {{ formatDateTime(article.completedTime) }}
          </span>
        </div>
      </header>

      <!-- 未完成 / 失败状态的提示 -->
      <a-alert
        v-if="article.status === 'PROCESSING' || article.status === 'PENDING'"
        class="state-alert"
        type="info"
        show-icon
        message="文章仍在生成中"
        description="稍后刷新页面即可查看完整内容。"
      />
      <a-alert
        v-else-if="article.status === 'FAILED'"
        class="state-alert"
        type="error"
        show-icon
        message="文章生成失败"
        :description="article.errorMessage || '后端未提供失败原因'"
      />

      <!-- 大纲 -->
      <section v-if="article.outline?.length" class="block outline-section">
        <h2 class="section-title">
          <OrderedListOutlined class="section-icon" />
          文章大纲
        </h2>
        <div class="outline-list">
          <div
            v-for="(item, index) in article.outline"
            :key="item.section ?? index"
            class="outline-item"
          >
            <div class="outline-title">{{ item.section ?? index + 1 }}. {{ item.title }}</div>
            <ul v-if="item.points?.length" class="outline-points">
              <li v-for="(point, pointIndex) in item.points" :key="pointIndex">{{ point }}</li>
            </ul>
          </div>
        </div>
      </section>

      <!-- 完整图文（接口返回 fullContent 时优先展示） -->
      <section v-if="article.fullContent" class="block content-section">
        <h2 class="section-title">
          <FileTextOutlined class="section-icon" />
          完整图文
        </h2>
        <div class="markdown-content" v-html="fullContentHtml"></div>
      </section>

      <!-- 普通正文（无 fullContent 时展示） -->
      <section v-else-if="article.content" class="block content-section">
        <h2 class="section-title">
          <FileTextOutlined class="section-icon" />
          文章正文
        </h2>
        <div class="markdown-content" v-html="contentHtml"></div>
      </section>

      <!-- 配图 -->
      <section v-if="article.images?.length" class="block images-section">
        <h2 class="section-title">
          <PictureOutlined class="section-icon" />
          文章配图
        </h2>
        <div class="images-grid">
          <figure
            v-for="(image, index) in article.images"
            :key="image.position ?? index"
            class="image-item"
          >
            <!-- 懒加载 + 异步解码，避免长文里几十张图同时请求 -->
            <img
              :src="image.url"
              :alt="image.description || `配图${index + 1}`"
              loading="lazy"
              decoding="async"
            />
            <figcaption class="image-info">
              <!-- method 是后端枚举值（如 UNSPLASH），展示中文更易读 -->
              <a-tag v-if="image.method" color="blue">{{ getImageMethodText(image.method) }}</a-tag>
              <span class="keywords">{{ image.keywords || image.description || '-' }}</span>
            </figcaption>
          </figure>
        </div>
      </section>

      <!-- 智能体执行日志：排查某个环节为什么慢或者失败 -->
      <section class="block logs-section">
        <h2 class="section-title">
          <RobotOutlined class="section-icon" />
          智能体执行日志
        </h2>
        <ExecutionLogsPanel :task-id="String(route.params.taskId ?? '')" />
      </section>

      <a-empty v-if="!hasContent" description="文章暂无内容" />
    </article>

    <!-- 加载失败或文章不存在 -->
    <a-empty v-else class="detail-empty" description="文章不存在或无权访问">
      <a-button type="primary" @click="goToList">返回列表</a-button>
    </a-empty>
  </div>
</template>

<script setup lang="ts">
/**
 * 文章详情页
 *
 * 展示大纲 + 正文（优先完整图文）+ 配图，并支持导出为 Markdown。
 *
 * @author ai-passage-creator
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  ArrowLeftOutlined,
  CopyOutlined,
  DownloadOutlined,
  FileTextOutlined,
  OrderedListOutlined,
  PictureOutlined,
  RobotOutlined,
} from '@ant-design/icons-vue'
import { getArticle } from '@/api/articleController'
import ExecutionLogsPanel from './components/ExecutionLogsPanel.vue'
import { markdownToHtml } from '@/utils/markdown'
import {
  buildArticleMarkdown,
  copyText,
  downloadMarkdown,
  formatDateTime,
  getImageMethodText,
  getStatusColor,
  getStatusText,
} from './articleHelpers'

const route = useRoute()
const router = useRouter()

/**
 * 详情数据结构
 *
 * 后端 ArticleVO 目前没有 fullContent 字段（BeanUtils 拷贝时被丢弃），
 * 但数据库与 SSE 消息里都有，这里显式声明为可选字段：
 * 拿到就展示图文合成结果，拿不到自动降级为 content，无需改动模板。
 */
type ArticleDetail = API.ArticleVO & { fullContent?: string }

const loading = ref(false)
const exporting = ref(false)
const article = ref<ArticleDetail | null>(null)

/** 正文 HTML（computed 缓存，避免每次重渲染都重新解析 Markdown） */
const contentHtml = computed(() => markdownToHtml(article.value?.content ?? ''))
const fullContentHtml = computed(() => markdownToHtml(article.value?.fullContent ?? ''))

/** 是否已有可展示的内容 */
const hasContent = computed(() =>
  Boolean(article.value?.content || article.value?.fullContent || article.value?.outline?.length),
)

/** 加载文章详情 */
const loadArticle = async () => {
  const taskId = route.params.taskId as string
  if (!taskId) {
    message.error('文章 ID 不存在')
    return
  }

  loading.value = true
  try {
    // BaseResponse<ArticleVO>，业务数据在 data.data
    const res = await getArticle({ taskId })
    article.value = res.data?.data ?? null

    if (!article.value) {
      message.warning(res.data?.message || '文章不存在')
    }
  } catch (error: any) {
    article.value = null
    message.error(error?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

/** 返回上一页；直接打开链接时退回列表页 */
const goBack = () => {
  if (window.history.length > 1) {
    router.back()
    return
  }
  void router.push('/article/list')
}

/** 回到列表页 */
const goToList = () => {
  void router.push('/article/list')
}

/** 复制全文 */
const copyContent = async () => {
  const text = article.value?.fullContent || article.value?.content || ''
  if (!text) {
    message.warning('暂无可复制的内容')
    return
  }
  try {
    await copyText(text)
    message.success('已复制到剪贴板')
  } catch (error) {
    console.error('复制失败:', error)
    message.error('复制失败，请手动选择文本')
  }
}

/** 导出 Markdown */
const exportMarkdown = () => {
  if (!article.value) {
    return
  }

  const markdown = buildArticleMarkdown(article.value)
  if (!markdown) {
    message.warning('暂无可导出的内容')
    return
  }

  exporting.value = true
  try {
    downloadMarkdown(markdown, article.value.mainTitle || article.value.topic || '文章')
    message.success('已导出 Markdown')
  } finally {
    exporting.value = false
  }
}

/** 路由参数变化时（同一组件复用）重新加载 */
watch(() => route.params.taskId, () => {
  void loadArticle()
})

onMounted(() => {
  void loadArticle()
})
</script>

<style scoped lang="scss">
$primary: var(--c-primary);

.article-detail-page {
  min-height: 100vh;
  padding: 24px;
  background: var(--c-bg);
  box-sizing: border-box;
}

.detail-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  max-width: 1000px;
  margin: 0 auto 20px;
}

.detail-skeleton,
.detail-card,
.detail-empty {
  display: block;
  max-width: 1000px;
  margin: 0 auto;
}

.detail-card {
  padding: 40px;
  background: var(--c-surface);
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(26, 26, 25, 0.06);
}

.detail-empty {
  padding: 60px 24px;
  background: var(--c-surface);
  border-radius: 12px;
}

/* 标题区 */
.title-section {
  padding-bottom: 20px;
  border-bottom: 1px solid var(--c-border);

  .main-title {
    margin: 0 0 12px;
    font-size: 30px;
    font-weight: 700;
    line-height: 1.4;
    color: var(--c-text-1);
  }

  .sub-title {
    margin: 0 0 16px;
    font-size: 16px;
    line-height: 1.6;
    color: var(--c-text-2);
  }

  .meta-info {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    align-items: center;
    font-size: 13px;
    color: var(--c-text-3);
  }
}

.state-alert {
  margin-top: 20px;
}

/* 区块通用 */
.block {
  margin-top: 32px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 20px;
  font-size: 19px;
  font-weight: 600;
  color: $primary;

  .section-icon {
    font-size: 18px;
  }
}

/* 大纲 */
.outline-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.outline-item {
  padding: 14px 16px;
  background: var(--c-surface-2);
  border-left: 3px solid $primary;
  border-radius: 6px;

  .outline-title {
    font-size: 15px;
    font-weight: 600;
    color: var(--c-text-1);
  }

  .outline-points {
    margin: 8px 0 0;
    padding-left: 20px;

    li {
      margin-bottom: 4px;
      font-size: 13px;
      line-height: 1.7;
      color: var(--c-text-2);

      &:last-child {
        margin-bottom: 0;
      }
    }
  }
}

/* 正文（v-html 插入，需要 :deep 穿透 scoped） */
.markdown-content {
  font-size: 16px;
  line-height: 1.9;
  color: var(--c-text-1);
  word-break: break-word;

  :deep(h1),
  :deep(h2),
  :deep(h3),
  :deep(h4) {
    margin: 28px 0 14px;
    font-weight: 600;
    line-height: 1.4;
    color: var(--c-text-1);
  }

  :deep(h1) {
    font-size: 26px;
  }

  :deep(h2) {
    padding-bottom: 8px;
    font-size: 22px;
    border-bottom: 2px solid var(--c-border);
  }

  :deep(h3) {
    font-size: 18px;
  }

  :deep(p) {
    margin: 0 0 16px;
    text-indent: 2em;
  }

  :deep(ul),
  :deep(ol) {
    margin: 0 0 16px;
    padding-left: 2em;
  }

  :deep(li) {
    margin-bottom: 8px;
  }

  :deep(img) {
    display: block;
    max-width: 100%;
    margin: 20px auto;
    border-radius: 8px;
  }

  :deep(blockquote) {
    margin: 0 0 16px;
    padding: 12px 16px;
    color: var(--c-text-2);
    background: var(--c-surface-2);
    border-left: 4px solid var(--c-border-strong);
  }

  :deep(code) {
    padding: 2px 6px;
    font-family: Consolas, Monaco, monospace;
    font-size: 0.9em;
    background: var(--c-bg-soft);
    border-radius: 4px;
  }

  :deep(pre) {
    margin: 0 0 16px;
    padding: 16px;
    overflow-x: auto;
    background: var(--c-bg-soft);
    border-radius: 8px;

    code {
      padding: 0;
      background: transparent;
    }
  }

  :deep(table) {
    width: 100%;
    margin-bottom: 16px;
    border-collapse: collapse;

    th,
    td {
      padding: 8px 12px;
      border: 1px solid var(--c-border);
    }

    th {
      background: var(--c-surface-2);
      font-weight: 600;
    }
  }
}

/* 配图 */
.images-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 20px;
}

.image-item {
  margin: 0;
  overflow: hidden;
  background: var(--c-surface-2);
  border-radius: 10px;
  box-shadow: 0 2px 8px rgba(26, 26, 25, 0.08);
  transition: transform 0.25s;

  &:hover {
    transform: translateY(-3px);
  }

  img {
    display: block;
    width: 100%;
    height: 180px;
    object-fit: cover;
    background: var(--c-border);
  }

  .image-info {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding: 10px 12px;

    .keywords {
      overflow: hidden;
      font-size: 12px;
      color: var(--c-text-3);
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }
}

/* 响应式 */
@media (max-width: 768px) {
  .article-detail-page {
    padding: 12px;
  }

  .detail-card {
    padding: 20px;
  }

  .title-section .main-title {
    font-size: 24px;
  }

  .images-grid {
    grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
    gap: 12px;
  }
}
</style>
