<template>
  <div class="article-create-page">
    <div class="create-layout">
      <!-- ==================== 左侧：智能体流程可视化 ==================== -->
      <aside class="sidebar-left" aria-label="智能体创作流程">
        <div class="sidebar-header">
          <h2 class="sidebar-title">创作流程</h2>
          <p class="sidebar-subtitle">智能体协作可视化</p>
        </div>

        <ol class="flow-timeline">
          <li
            v-for="(step, index) in agentSteps"
            :key="step.title"
            class="flow-item"
            :class="{
              active: currentStep === index && isCreating,
              completed: currentStep > index,
              pending: currentStep < index,
            }"
            :aria-current="currentStep === index && isCreating ? 'step' : undefined"
          >
            <div class="flow-indicator">
              <LoadingOutlined v-if="currentStep === index && isCreating" spin class="spin-icon" />
              <CheckCircleOutlined v-else-if="currentStep > index" />
              <span v-else class="step-number">{{ index + 1 }}</span>
            </div>
            <div class="flow-content">
              <div class="flow-title">{{ step.title }}</div>
              <div class="flow-desc">{{ step.description }}</div>
              <div v-if="currentStep === index && isCreating" class="flow-status">
                <span class="status-dot" aria-hidden="true"></span>
                执行中...
              </div>
            </div>
          </li>
        </ol>
      </aside>

      <!-- ==================== 中间：主内容区 ==================== -->
      <main ref="mainContentRef" class="main-content" @scroll.passive="handleContentScroll">
        <!-- 阶段切换由 currentPhase 驱动；Transition 提供滑入滑出的动画 -->
        <Transition name="fade-slide" mode="out-in">
        <!-- ---------- 阶段一：输入选题 ---------- -->
        <section v-if="currentPhase === 'INPUT'" key="input" class="input-state">
          <div class="input-card">
            <header class="input-header">
              <h1 class="input-title">创作新文章</h1>
              <p class="input-subtitle">输入选题，AI 帮你生成爆款文章</p>
            </header>

            <div class="input-area">
              <a-textarea
                v-model:value="topic"
                placeholder="请输入您想创作的文章选题，例如：2026年AI如何改变职场"
                :rows="6"
                :maxlength="500"
                show-count
                class="topic-textarea"
                aria-label="文章选题"
                @press-enter="handlePressEnter"
              />

              <!-- 文章风格选择 -->
              <div class="style-section">
                <div class="section-header">
                  <span class="section-title">文章风格</span>
                </div>
                <a-radio-group v-model:value="selectedStyle" class="style-group">
                  <a-radio value="">默认</a-radio>
                  <a-radio
                    v-for="style in ARTICLE_STYLE_OPTIONS"
                    :key="style.value"
                    :value="style.value"
                  >
                    {{ style.label }}
                  </a-radio>
                </a-radio-group>
              </div>

              <!-- 配图方式选择 -->
              <div class="image-methods-section">
                <div class="section-header">
                  <span class="section-title">配图方式</span>
                  <span class="section-tip">
                    （不选择表示支持所有方式<template v-if="!canUsePremiumImages"
                      >，带 VIP 标记的方式需开通会员</template
                    >）
                  </span>
                </div>
                <a-checkbox-group v-model:value="selectedImageMethods" class="methods-group">
                  <a-checkbox
                    v-for="method in IMAGE_METHOD_OPTIONS"
                    :key="method.value"
                    :value="method.value"
                    :title="
                      isImageMethodDisabled(method)
                        ? `「${method.label}」为会员专属功能`
                        : method.description
                    "
                    :disabled="isImageMethodDisabled(method)"
                  >
                    {{ method.label }}
                    <!-- 会员专属标识：普通用户可见但不可选，避免「有功能却找不到入口」的困惑 -->
                    <span v-if="method.vipOnly" class="vip-method-mark">VIP</span>
                  </a-checkbox>
                </a-checkbox-group>
              </div>

              <a-button
                type="primary"
                size="large"
                block
                :loading="isCreating"
                :disabled="!topic.trim() || !hasQuota"
                class="create-btn"
                @click="startCreate"
              >
                <template #icon>
                  <RocketOutlined />
                </template>
                开始创作
              </a-button>

              <p class="input-hint">提示：按 Ctrl + Enter 也可开始创作</p>

              <div v-if="!hasQuota" class="quota-warning" role="alert">
                <WarningOutlined />
                <span>配额已用完，无法创建文章</span>
              </div>
            </div>
          </div>
        </section>

        <!-- ---------- 阶段二：生成标题方案 ---------- -->
        <section
          v-else-if="currentPhase === 'TITLE_GENERATING'"
          key="title-generating"
          class="loading-stage"
        >
          <a-spin size="large" />
          <h3>AI 正在生成标题方案...</h3>
          <p>稍等片刻，即将为您呈现多个精彩标题</p>
        </section>

        <!-- ---------- 阶段三：选择标题方案 ---------- -->
        <TitleSelectingStage
          v-else-if="currentPhase === 'TITLE_SELECTING'"
          key="title-selecting"
          :title-options="titleOptions"
          :loading="confirmLoading"
          @confirm="handleConfirmTitle"
        />

        <!-- ---------- 阶段四：生成大纲（流式预览） ---------- -->
        <section
          v-else-if="currentPhase === 'OUTLINE_GENERATING'"
          key="outline-generating"
          class="outline-generating-state"
        >
          <div v-if="article.mainTitle" class="preview-header">
            <h1 class="article-title">{{ article.mainTitle }}</h1>
            <p class="article-subtitle">{{ article.subTitle }}</p>
          </div>

          <div class="outline-preview">
            <div class="section-label">
              <BulbOutlined />
              <span>AI 正在规划文章大纲</span>
              <span v-if="isOutlineStreaming" class="typing-cursor" aria-hidden="true">|</span>
            </div>
            <div v-if="parsedOutline.length > 0" class="outline-list">
              <div
                v-for="(item, index) in parsedOutline"
                :key="item.section ?? index"
                class="outline-item"
              >
                <div class="outline-title">{{ item.section ?? index + 1 }}. {{ item.title }}</div>
                <ul v-if="item.points?.length" class="outline-points">
                  <li v-for="(point, idx) in item.points" :key="idx">{{ point }}</li>
                </ul>
              </div>
            </div>
          </div>
        </section>

        <!-- ---------- 阶段五：编辑大纲 ---------- -->
        <OutlineEditingStage
          v-else-if="currentPhase === 'OUTLINE_EDITING'"
          key="outline-editing"
          :outline="outline"
          :loading="confirmLoading"
          :task-id="taskId"
          @confirm="handleConfirmOutline"
        />

        <!-- ---------- 阶段六：生成正文与配图 ---------- -->
        <section v-else-if="currentPhase === 'CONTENT_GENERATING'" key="content-generating" class="creating-state">
          <!-- 标题预览 -->
          <div v-if="article.mainTitle" class="preview-header">
            <h1 class="article-title">{{ article.mainTitle }}</h1>
            <p class="article-subtitle">{{ article.subTitle }}</p>
          </div>

          <!-- 大纲预览（流式解析展示） -->
          <div v-if="outlineRaw || displayOutline.length" class="outline-preview">
            <div class="section-label">
              <OrderedListOutlined />
              <span>文章大纲</span>
              <span v-if="isOutlineStreaming" class="typing-cursor" aria-hidden="true">|</span>
            </div>
            <div class="outline-list">
              <div
                v-for="(item, index) in displayOutline"
                :key="item.section ?? index"
                class="outline-item"
              >
                <div class="outline-title">{{ item.section ?? index + 1 }}. {{ item.title }}</div>
                <ul v-if="item.points?.length" class="outline-points">
                  <li v-for="(point, pointIndex) in item.points" :key="pointIndex">{{ point }}</li>
                </ul>
              </div>
            </div>
          </div>

          <!-- 正文预览（流式输出，打字机效果） -->
          <div v-if="article.content" class="content-preview">
            <div class="section-label">
              <EditOutlined />
              <span>文章正文</span>
              <span v-if="isStreaming" class="typing-cursor" aria-hidden="true">|</span>
            </div>
            <!-- aria-live 让读屏软件能感知流式新增内容 -->
            <div
              class="markdown-body"
              aria-live="polite"
              :aria-busy="isStreaming"
              v-html="contentHtml"
            ></div>
          </div>

          <!-- 配图生成进度 -->
          <div v-if="currentStep === 4" class="image-progress-box">
            <div class="progress-header">
              <PictureOutlined />
              <span>正在生成配图</span>
            </div>
            <a-progress
              :percent="imageProgress"
              status="active"
              :stroke-color="{ from: '#22C55E', to: '#16A34A' }"
            />
            <p class="progress-hint">{{ imageCount }}/{{ totalImages }} 张图片已完成</p>
          </div>

          <!-- 首屏加载占位 -->
          <div v-if="currentStep === 0 && !article.mainTitle" class="loading-placeholder">
            <a-spin size="large" />
            <p>AI 正在构思标题...</p>
          </div>
        </section>

        <!-- ---------- 阶段七：创作完成 ---------- -->
        <section v-else-if="currentPhase === 'COMPLETED'" key="completed" class="completed-state">
          <div class="success-banner">
            <CheckCircleFilled class="success-icon" />
            <div class="success-text">
              <h2>文章创作完成！</h2>
              <p>共 {{ article.images?.length ?? 0 }} 张配图已合成到正文，可导出或查看详情</p>
            </div>
          </div>

          <div class="completed-actions">
            <a-button type="primary" @click="viewArticle">
              <template #icon><EyeOutlined /></template>
              查看详情
            </a-button>
            <a-button @click="exportCurrent">
              <template #icon><DownloadOutlined /></template>
              导出 Markdown
            </a-button>
            <a-button @click="copyContent">
              <template #icon><CopyOutlined /></template>
              复制全文
            </a-button>
            <a-button type="dashed" @click="resetCreate">
              <template #icon><ReloadOutlined /></template>
              再创作一篇
            </a-button>
          </div>

          <article class="completed-article">
            <h1 class="final-title">{{ article.mainTitle }}</h1>
            <p v-if="article.subTitle" class="final-subtitle">{{ article.subTitle }}</p>

            <!-- 优先展示图文合成结果，为空时降级为纯文本正文 -->
            <div v-if="finalHtml" class="markdown-body" v-html="finalHtml"></div>
            <a-empty v-else description="正文内容缺失，可查看详情页或重新创作" />

            <!--
              MERGE_COMPLETE 消息丢失时 fullContent 为空，正文里就没有插图，
              这里单独把配图补上，避免生成的图片白白丢掉
            -->
            <section v-if="!article.fullContent && article.images?.length" class="completed-images">
              <h2 class="section-label">
                <PictureOutlined />
                <span>文章配图</span>
              </h2>
              <div class="images-grid">
                <figure
                  v-for="(image, index) in article.images"
                  :key="image.position ?? index"
                  class="image-item"
                >
                  <img
                    :src="image.url"
                    :alt="image.description || `配图${index + 1}`"
                    loading="lazy"
                    decoding="async"
                  />
                  <figcaption class="image-info">
                    <!-- 展示每张图的来源（Unsplash 图库 / Mermaid 图表 / 表情包 …） -->
                    <a-tag v-if="image.method" color="blue">
                      {{ getImageMethodText(image.method) }}
                    </a-tag>
                    <span class="keywords">{{ image.keywords || image.description || '-' }}</span>
                  </figcaption>
                </figure>
              </div>
            </section>
          </article>
        </section>
        </Transition>
      </main>

      <!-- ==================== 右侧：辅助面板 ==================== -->
      <aside class="sidebar-right" aria-label="辅助信息">
        <!-- 热门选题 -->
        <div class="panel-card">
          <h3 class="panel-title">🔥 热门选题</h3>
          <ul class="topic-list">
            <li v-for="item in exampleTopics" :key="item">
              <button type="button" class="topic-chip" :disabled="isCreating" @click="applyTopic(item)">
                {{ item }}
              </button>
            </li>
          </ul>
        </div>

        <!-- 创作进行中的进度 -->
        <div v-if="isCreating && !isCompleted" class="panel-card">
          <h3 class="panel-title">📊 创作进度</h3>
          <a-progress type="circle" :percent="progressPercent" :width="96" :stroke-color="'#667eea'" />
          <dl class="task-meta">
            <div class="meta-row">
              <dt>当前步骤</dt>
              <dd>{{ currentStepText }}</dd>
            </div>
            <div class="meta-row">
              <dt>配图</dt>
              <dd>{{ imageCount }}/{{ totalImages }}</dd>
            </div>
            <div class="meta-row">
              <dt>任务 ID</dt>
              <dd class="task-id" :title="taskId">{{ taskId || '-' }}</dd>
            </div>
          </dl>
        </div>

        <!-- 完成后的统计 -->
        <div v-if="isCompleted" class="panel-card stats-section">
          <h3 class="panel-title">
            <BarChartOutlined />
            文章统计
          </h3>
          <div class="stats-grid">
            <div class="stat-item">
              <div class="stat-value">{{ articleLength }}</div>
              <div class="stat-label">字数</div>
            </div>
            <div class="stat-item">
              <div class="stat-value">{{ article.images?.length ?? 0 }}</div>
              <div class="stat-label">配图</div>
            </div>
          </div>
        </div>
      </aside>
    </div>

    <!-- 创作失败提示 -->
    <a-modal v-model:open="errorVisible" title="创作失败" :footer="null">
      <p class="error-message">{{ errorMessage }}</p>
      <div class="error-actions">
        <a-button type="primary" @click="retryCreate">重新创作</a-button>
        <a-button @click="errorVisible = false">关闭</a-button>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
/**
 * AI 文章创作页
 *
 * 三栏布局：左侧智能体流程 / 中间流式内容 / 右侧辅助信息。
 * 通过 SSE 实时接收后端推送的创作进度，实现大纲与正文的流式渲染。
 *
 * @author ai-passage-creator
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  BarChartOutlined,
  CheckCircleFilled,
  CheckCircleOutlined,
  CopyOutlined,
  DownloadOutlined,
  EditOutlined,
  EyeOutlined,
  LoadingOutlined,
  OrderedListOutlined,
  PictureOutlined,
  ReloadOutlined,
  RocketOutlined,
  WarningOutlined,
} from '@ant-design/icons-vue'
import { confirmOutline, confirmTitle, createArticle, getArticle } from '@/api/articleController'
import { openSse, type SseConnection } from '@/utils/sse'
import { markdownToHtml } from '@/utils/markdown'
import request from '@/request'
import { useLoginUserStore } from '@/stores/loginUser'
import {
  ARTICLE_STYLE_OPTIONS,
  BASIC_IMAGE_METHOD_VALUES,
  buildArticleMarkdown,
  copyText,
  downloadMarkdown,
  getImageMethodText,
  IMAGE_METHOD_OPTIONS,
  isVipOnlyImageMethod,
  type ImageMethodOption,
} from './articleHelpers'
import TitleSelectingStage from './components/TitleSelectingStage.vue'
import OutlineEditingStage from './components/OutlineEditingStage.vue'

const router = useRouter()
const route = useRoute()

// ==================== 常量 ====================

/** 智能体步骤（对应后端 6 个步骤） */
const agentSteps = [
  { title: '生成标题', description: 'AI 分析选题，生成吸睛标题' },
  { title: '规划大纲', description: '构建文章结构，理清脉络' },
  { title: '撰写正文', description: '流式生成高质量文章内容' },
  { title: '分析配图', description: '智能分析配图需求和位置' },
  { title: '生成配图', description: '自动匹配高清无版权图片' },
  { title: '图文合成', description: '将配图插入正文，完美呈现' },
]

/** 示例选题 */
const exampleTopics = [
  '2026年AI如何改变职场',
  '程序员如何提升竞争力',
  '远程办公的利与弊',
  '如何培养深度思考',
  '新能源汽车趋势',
  '健康饮食指南',
]

// ==================== 类型 ====================

/**
 * 后端推送的进度消息
 *
 * 字段与 ArticleAsyncService#buildMessageData 构建的 Map 一一对应，
 * 注意这些字段都在**消息顶层**，并没有嵌套在 data 里。
 */
interface ProgressMessage {
  type: string
  /** AGENT2_STREAMING / AGENT3_STREAMING：本次追加的片段 */
  content?: string
  /** AGENT1_COMPLETE：标题结果 */
  title?: { mainTitle?: string; subTitle?: string }
  /** TITLES_GENERATED：标题方案列表（等待用户选择） */
  titleOptions?: Array<{ mainTitle?: string; subTitle?: string }>
  /** AGENT2_COMPLETE / OUTLINE_GENERATED：完整大纲（数组） */
  outline?: API.OutlineItem[]
  /** AGENT4_COMPLETE：配图需求列表，用于计算配图总数 */
  imageRequirements?: Array<{ position?: number; type?: string; sectionTitle?: string; keywords?: string }>
  /** IMAGE_COMPLETE：单张配图结果 */
  image?: API.ImageItem
  /** AGENT5_COMPLETE：配图结果列表 */
  images?: API.ImageItem[]
  /** MERGE_COMPLETE：图文合成后的完整 Markdown */
  fullContent?: string
  /** ERROR：错误信息 */
  message?: string
}

/** 文章草稿：在 VO 基础上补充 SSE 独有字段（ArticleVO 中没有 fullContent） */
type ArticleDraft = API.ArticleVO & { fullContent?: string }

/**
 * 创作阶段（三阶段人机协作流程）
 *
 * 完全由后端推送的 SSE 消息驱动，前端只负责按阶段切换 UI：
 * 生成标题 → 选择标题 → 生成大纲 → 编辑大纲 → 生成正文 → 完成
 */
type CreatePhase =
  | 'INPUT'
  | 'TITLE_GENERATING'
  | 'TITLE_SELECTING'
  | 'OUTLINE_GENERATING'
  | 'OUTLINE_EDITING'
  | 'CONTENT_GENERATING'
  | 'COMPLETED'

// ==================== 页面状态 ====================

/** 当前创作阶段 */
const currentPhase = ref<CreatePhase>('INPUT')
/** 标题方案（TITLES_GENERATED 推送） */
const titleOptions = ref<Array<{ mainTitle: string; subTitle: string }>>([])
/** 待编辑的大纲（OUTLINE_GENERATED 推送） */
const outline = ref<API.OutlineSection[]>([])
/** 确认标题 / 确认大纲的请求状态 */
const confirmLoading = ref(false)

const topic = ref('')
/** 创作参数：文章风格，空字符串表示使用默认风格 */
const selectedStyle = ref('')
/** 创作参数：允许的配图方式，空数组表示支持全部方式 */
const selectedImageMethods = ref<string[]>([])
const isCreating = ref(false)
const isCompleted = ref(false)
const isStreaming = ref(false)
const isOutlineStreaming = ref(false)
const currentStep = ref(0)
const taskId = ref('')
const errorVisible = ref(false)
const errorMessage = ref('')

/** 配额开关：后续接入 VIP 后改为真实接口返回值即可，模板无需改动 */
const hasQuota = ref(true)

// ==================== 会员配图权限 ====================

const loginUserStore = useLoginUserStore()

/** 是否具备高级配图权限：vip 与 admin 可用 AI 生图 / SVG 示意图 */
const canUsePremiumImages = computed(() => {
  const role = loginUserStore.loginUser.userRole
  return role === 'vip' || role === 'admin'
})

/** 某个配图方式当前是否被禁用（会员专属且当前身份不够） */
const isImageMethodDisabled = (method: ImageMethodOption): boolean =>
  Boolean(method.vipOnly) && !canUsePremiumImages.value

/**
 * 身份变化后清理已选中但已不可用的配图方式
 *
 * 例如会员被撤销、或退出登录后仍停留在本页，之前勾选的会员专属方式必须移除，
 * 否则提交时会把当前身份不该用的参数带给后端。
 */
watch(canUsePremiumImages, (allowed) => {
  if (allowed) {
    return
  }
  const before = selectedImageMethods.value.length
  selectedImageMethods.value = selectedImageMethods.value.filter(
    (value) => !isVipOnlyImageMethod(value),
  )
  if (selectedImageMethods.value.length !== before) {
    message.info('「AI 生图」与「SVG 示意图」为会员专属，已从当前选择中移除')
  }
})

/** 大纲流式原始字符串（后端分块推送的 JSON 片段） */
const outlineRaw = ref('')

/** 配图进度 */
const imageCount = ref(0)
const totalImages = ref(5)
const imageProgress = ref(0)

/** 文章数据 */
const article = ref<ArticleDraft>({
  mainTitle: '',
  subTitle: '',
  content: '',
  fullContent: '',
  images: [],
})

/** 内容区域引用（用于自动滚动） */
const mainContentRef = ref<HTMLElement | null>(null)

/** 是否自动跟随滚动（用户手动上滚时暂停，避免打断阅读） */
const autoFollow = ref(true)

// SSE 连接句柄（无需响应式）；连接生命周期由 utils/sse 的 openSse 统一管理
let sseConnection: SseConnection | null = null
let scrollFrame = 0

// ==================== 大纲流式解析 ====================

/**
 * 尝试把一段文本解析成大纲数组
 *
 * 兼容两种后端形态：
 * - 流式阶段：LLM 输出的 `{"sections":[...]}`
 * - 完成阶段：`AGENT2_COMPLETE` 直接推送数组
 */
const tryParseOutline = (text: string): API.OutlineItem[] | null => {
  try {
    const parsed = JSON.parse(text)
    if (Array.isArray(parsed)) {
      return parsed as API.OutlineItem[]
    }
    if (Array.isArray(parsed?.sections)) {
      return parsed.sections as API.OutlineItem[]
    }
    return null
  } catch {
    return null
  }
}

/**
 * 容错解析流式大纲
 *
 * 后端一边生成一边推送，收到的片段可能是 `{"sections":[{"title":"引言",...},{"titl` 这种
 * 截断在半路的字符串，直接 JSON.parse 必然抛错。策略是：
 * 1）先尝试整体解析；
 * 2）失败则找到最后一个完整的 `}`，在此截断并补 `]` 强行闭合数组。
 * 这样已传输完的章节能立即渲染，用户不必等大纲全部生成完。
 */
const parsedOutline = computed<API.OutlineItem[]>(() => {
  const raw = outlineRaw.value.trim()
  if (!raw) {
    return []
  }

  const complete = tryParseOutline(raw)
  if (complete) {
    return complete
  }

  const arrayStart = raw.indexOf('[')
  if (arrayStart === -1) {
    return []
  }

  const rest = raw.slice(arrayStart)
  const lastBrace = rest.lastIndexOf('}')
  if (lastBrace <= 0) {
    return []
  }

  return tryParseOutline(`${rest.slice(0, lastBrace + 1)}]`) ?? []
})

/** 展示用大纲：优先使用后端完成消息里的权威结果 */
const displayOutline = computed<API.OutlineItem[]>(() =>
  article.value.outline?.length ? article.value.outline : parsedOutline.value,
)

// ==================== Markdown 渲染 ====================

/**
 * 正文 HTML
 *
 * 用 computed 而不是在模板里直接调用函数：流式期间 currentStep、光标等状态频繁变化会
 * 触发重渲染，computed 的缓存可避免重复解析同一段长文本。
 */
const contentHtml = computed(() => markdownToHtml(article.value.content ?? ''))

/** 完成态 HTML：优先展示图文合成结果 */
const finalHtml = computed(() => markdownToHtml(article.value.fullContent || article.value.content || ''))

// ==================== 派生状态 ====================

const progressPercent = computed(() => {
  if (isCompleted.value) {
    return 100
  }
  return Math.min(99, Math.round((currentStep.value / agentSteps.length) * 100))
})

const currentStepText = computed(() => {
  if (isCompleted.value) {
    return '已完成'
  }
  return agentSteps[currentStep.value]?.title ?? '准备中'
})

/** 完成后的字数统计 */
const articleLength = computed(() => (article.value.fullContent || article.value.content || '').length)

// ==================== 滚动控制 ====================

/**
 * requestAnimationFrame 节流
 *
 * 流式期间每个 chunk 都会触发滚动，直接写 scrollTop 会造成大量强制同步布局。
 */
const scheduleScrollToBottom = () => {
  if (!autoFollow.value || scrollFrame) {
    return
  }
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0
    const el = mainContentRef.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

/** 距底部 80px 内视为「跟随中」，用户上滚后自动暂停跟随 */
const handleContentScroll = () => {
  const el = mainContentRef.value
  if (!el) {
    return
  }
  autoFollow.value = el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

// ==================== SSE ====================

/**
 * 阶段结束消息：服务端推完这些消息后会主动 complete 连接。
 * 前端提前把它们识别为终态，才能干净地调用 close()，
 * 而不是等服务端断开后触发 onerror 再走异常分支。
 */
const PHASE_END_MESSAGES = ['TITLES_GENERATED', 'OUTLINE_GENERATED']

/** SSE 异常后的自动恢复次数，避免服务端长期不可用时无限重连 */
let sseRetryCount = 0
const MAX_SSE_RETRY = 1

/**
 * 建立进度 SSE 连接
 *
 * utils/sse 的 openSse 不对路径做假设，所以这里基于 request 实例的 baseURL 拼出绝对地址：
 * 项目没有配置 vite proxy，相对路径会打到 dev server 上导致 404。
 *
 * 分工：openSse 负责连接生命周期（消息解析、终态关闭、吞掉终态后的伪 error），
 * 页面只负责业务决策——异常时提示什么、怎么收尾。
 */
const createProgressSource = (id: string) => {
  // 重连场景下旧连接可能仍开着，先释放，避免引用被覆盖后泄漏
  sseConnection?.close()
  sseConnection = null

  const baseURL = (request.defaults.baseURL as string) || '/api'
  const url = `${baseURL.replace(/\/+$/, '')}/article/progress/${encodeURIComponent(id)}`

  sseConnection = openSse<ProgressMessage>({
    url,
    // 三阶段协作的每个阶段都会主动关闭连接（用户思考期间不需要长连接），
    // 因此阶段完成消息同样视为终态
    isTerminal: (payload) =>
      payload.type === 'ALL_COMPLETE' ||
      payload.type === 'ERROR' ||
      PHASE_END_MESSAGES.includes(payload.type),
    onMessage: handleSSEMessage,
    onError: () => {
      // 能走到这里说明不是终态后的正常余波（那种已被 openSse 吞掉），而是真的断了。
      // 但先别急着报错：先用详情接口核对服务端真实状态。
      // 常见情况是「最后一条消息在传输途中断开」，此时阶段其实已经推进，
      // 恢复界面比弹一个错误提示有用得多。
      if (!isCreating.value) {
        return
      }
      void recoverFromSseError()
    },
    onClose: () => {
      sseConnection = null
    },
  })
}

/** 处理 SSE 消息 */
const handleSSEMessage = (msg: ProgressMessage) => {
  // 能收到消息说明连接是通的，重置失败计数，允许下次异常时再自动恢复
  sseRetryCount = 0
  switch (msg.type) {
    case 'AGENT1_COMPLETE': {
      // 标题方案生成完成（具体方案由随后的 TITLES_GENERATED 下发）
      currentPhase.value = 'TITLE_GENERATING'
      currentStep.value = 1
      break
    }
    case 'TITLES_GENERATED': {
      // 标题方案生成完成，等待用户选择
      currentPhase.value = 'TITLE_SELECTING'
      isCreating.value = false
      titleOptions.value = (msg.titleOptions ?? []).map((item) => ({
        mainTitle: item.mainTitle ?? '',
        subTitle: item.subTitle ?? '',
      }))
      break
    }
    case 'AGENT2_STREAMING': {
      // 大纲流式输出：让用户实时看到 AI 正在规划的结构，而不是干等转圈
      currentPhase.value = 'OUTLINE_GENERATING'
      isOutlineStreaming.value = true
      outlineRaw.value += msg.content ?? ''
      scheduleScrollToBottom()
      break
    }
    case 'OUTLINE_GENERATED': {
      // 大纲生成完成，等待用户编辑确认
      currentPhase.value = 'OUTLINE_EDITING'
      isOutlineStreaming.value = false
      isCreating.value = false
      outline.value = Array.isArray(msg.outline) ? msg.outline : []
      break
    }
    case 'AGENT2_COMPLETE': {
      // 大纲流式结束（最终数据以 OUTLINE_GENERATED 为准）
      isOutlineStreaming.value = false
      currentStep.value = 2
      if (Array.isArray(msg.outline)) {
        article.value.outline = msg.outline
      }
      break
    }
    case 'AGENT3_STREAMING': {
      // 正文流式输出
      currentPhase.value = 'CONTENT_GENERATING'
      isStreaming.value = true
      article.value.content = `${article.value.content ?? ''}${msg.content ?? ''}`
      scheduleScrollToBottom()
      break
    }
    case 'AGENT3_COMPLETE': {
      isStreaming.value = false
      currentStep.value = 3
      break
    }
    case 'AGENT4_COMPLETE': {
      // 配图需求分析完成，拿到总数才能算进度
      currentStep.value = 4
      if (msg.imageRequirements?.length) {
        totalImages.value = msg.imageRequirements.length
      }
      break
    }
    case 'IMAGE_COMPLETE': {
      // 单张配图完成
      imageCount.value += 1
      imageProgress.value = totalImages.value
        ? Math.round((imageCount.value / totalImages.value) * 100)
        : 0
      if (msg.image) {
        article.value.images = [...(article.value.images ?? []), msg.image]
      }
      break
    }
    case 'AGENT5_COMPLETE': {
      currentStep.value = 5
      // 服务端给出完整列表，直接覆盖逐张累积的结果
      if (Array.isArray(msg.images)) {
        article.value.images = msg.images
        imageCount.value = msg.images.length
        totalImages.value = Math.max(totalImages.value, msg.images.length)
        imageProgress.value = 100
      }
      break
    }
    case 'MERGE_COMPLETE': {
      // 图文合成完成
      article.value.fullContent = msg.fullContent ?? ''
      scheduleScrollToBottom()
      break
    }
    case 'ALL_COMPLETE': {
      currentPhase.value = 'COMPLETED'
      currentStep.value = agentSteps.length
      isCompleted.value = true
      isCreating.value = false
      isStreaming.value = false
      isOutlineStreaming.value = false
      imageProgress.value = 100
      message.success('文章创作完成！')
      void ensureFinalArticle()
      break
    }
    case 'ERROR': {
      errorMessage.value = msg.message || '创作失败'
      errorVisible.value = true
      isCreating.value = false
      // 任务已失败，退回输入态让用户重新创作
      currentPhase.value = 'INPUT'
      break
    }
    default:
      // 未知消息类型不影响主流程
      console.warn('未处理的 SSE 消息类型:', msg.type)
  }
}

/**
 * 兜底补齐最终内容
 *
 * 背景：后端 SseEmitterManager 在 emitter 不存在时会直接丢弃消息，建连时机稍晚就可能漏掉
 * 流式分片；而 ALL_COMPLETE 是在 saveArticleContent 之后才推送的，此时查详情能拿到权威数据。
 *
 * 注意：详情接口（ArticleVO）并不返回 fullContent，所以这里**补不到**图文合成结果，
 * 它能补的只有 content / outline / images / 标题。因此判断"是否已有可展示内容"必须和
 * 完成态的渲染口径保持一致——渲染用的是 `fullContent || content`，两者任一有值都不必再请求。
 * 新增mainTitle验证，保证标题不会空白
 */
const ensureFinalArticle = async () => {
  if (article.value.content || article.value.fullContent || article.value.mainTitle || !taskId.value) {
    return
  }

  try {
    const res = await getArticle({ taskId: taskId.value })
    const detail = res.data?.data
    if (!detail) {
      return
    }

    // 逐字段合并，只接受后端确实返回了值的字段：
    // 直接展开 detail 会让 null 覆盖掉流式期间已累积的正文
    article.value = {
      ...article.value,
      mainTitle: detail.mainTitle || article.value.mainTitle,
      subTitle: detail.subTitle || article.value.subTitle,
      content: detail.content || article.value.content,
      outline: detail.outline?.length ? detail.outline : article.value.outline,
      images: detail.images?.length ? detail.images : article.value.images,
    }
  } catch (error) {
    console.error('补齐文章详情失败:', error)
  }
}

// ==================== 状态恢复 ====================

/**
 * 按后端详情恢复页面阶段与数据
 *
 * SSE 是「实时事件流」，错过就无法回溯；而三阶段流程中用户随时可能刷新，
 * 因此必须有一个可查询的真相源（ArticleVO 的 phase + 各阶段产物）来重建界面。
 */
const restoreFromDetail = (detail: API.ArticleVO) => {
  taskId.value = detail.taskId ?? ''
  topic.value = detail.topic ?? topic.value

  article.value = {
    ...article.value,
    mainTitle: detail.mainTitle ?? '',
    subTitle: detail.subTitle ?? '',
    content: detail.content ?? '',
    outline: detail.outline ?? [],
    images: detail.images ?? [],
  }

  // 整体状态优先于阶段：完成 / 失败时不再看 phase
  if (detail.status === 'COMPLETED') {
    currentPhase.value = 'COMPLETED'
    isCompleted.value = true
    isCreating.value = false
    currentStep.value = agentSteps.length
    return
  }
  if (detail.status === 'FAILED') {
    currentPhase.value = 'INPUT'
    errorMessage.value = detail.errorMessage || '创作失败'
    errorVisible.value = true
    isCreating.value = false
    return
  }

  switch (detail.phase) {
    case 'TITLE_SELECTING':
      currentPhase.value = 'TITLE_SELECTING'
      titleOptions.value = (detail.titleOptions ?? []).map((item) => ({
        mainTitle: item.mainTitle ?? '',
        subTitle: item.subTitle ?? '',
      }))
      isCreating.value = false
      break
    case 'OUTLINE_EDITING':
      currentPhase.value = 'OUTLINE_EDITING'
      outline.value = detail.outline ?? []
      isCreating.value = false
      break
    case 'TITLE_GENERATING':
    case 'OUTLINE_GENERATING':
    case 'CONTENT_GENERATING':
      currentPhase.value = detail.phase
      isCreating.value = true
      break
    default:
      // PENDING 或未知阶段：回到输入态，避免误展示一个空壳界面
      currentPhase.value = 'INPUT'
      isCreating.value = false
  }
}

/**
 * SSE 异常后的兜底恢复
 *
 * 断线时服务端可能已经推进了阶段（只是最后一条消息没送达），
 * 因此以详情接口为准重建界面；若确认仍在生成中，则重连一次继续接收。
 */
const recoverFromSseError = async () => {
  if (!taskId.value) {
    isCreating.value = false
    return
  }
  if (sseRetryCount >= MAX_SSE_RETRY) {
    isCreating.value = false
    message.error('创作进度连接中断，可刷新页面查看结果')
    return
  }
  sseRetryCount += 1

  try {
    const res = await getArticle({ taskId: taskId.value })
    const detail = res.data?.data
    if (detail) {
      restoreFromDetail(detail)
      // 仍在生成中：重连继续接收（收到任意消息后计数会被重置）
      if (currentPhase.value.endsWith('_GENERATING')) {
        createProgressSource(taskId.value)
      }
      return
    }
  } catch (error) {
    console.error('SSE 异常后恢复失败:', error)
  }

  isCreating.value = false
  message.error('创作进度连接中断，可刷新页面查看结果')
}

/** 通过 taskId 恢复创作现场（刷新页面 / 从列表页「继续编辑」进入） */
const resumeFromTaskId = async (id: string) => {
  taskId.value = id
  try {
    const res = await getArticle({ taskId: id })
    const detail = res.data?.data
    if (!detail) {
      message.warning('未找到该创作任务，请重新开始')
      return
    }

    restoreFromDetail(detail)

    // 只有「生成中」阶段需要继续接收推送；
    // 「等待用户操作」与终态阶段服务端不会主动推消息，建连也只是空等
    if (currentPhase.value.endsWith('_GENERATING')) {
      createProgressSource(id)
    }
  } catch (error) {
    console.error('恢复创作任务失败:', error)
    message.error('恢复创作任务失败，请重新开始')
  }
}

// ==================== 创作流程 ====================

/** 清空上一轮的流式中间状态 */
const resetStreamingState = () => {
  outlineRaw.value = ''
  imageCount.value = 0
  totalImages.value = 5
  imageProgress.value = 0
  isStreaming.value = false
  isOutlineStreaming.value = false
  article.value = {
    mainTitle: '',
    subTitle: '',
    content: '',
    fullContent: '',
    images: [],
    outline: [],
  }
}

/** 开始创作：创建任务并进入阶段一（生成标题方案） */
const startCreate = async () => {
  const trimmed = topic.value.trim()
  if (!trimmed) {
    message.warning('请输入选题')
    return
  }

  // 清理上一次可能残留的连接
  sseConnection?.close()
  sseConnection = null

  resetStreamingState()
  isCreating.value = true
  isCompleted.value = false
  // 阶段一：后端开始生成多个标题方案
  currentPhase.value = 'TITLE_GENERATING'
  currentStep.value = 0
  autoFollow.value = true

  try {
    // 只提交当前身份可用的配图方式，防止身份变化或异常状态绕过 UI 上的禁用
    const usableMethods = selectedImageMethods.value.filter((value) => {
      const method = IMAGE_METHOD_OPTIONS.find((item) => item.value === value)
      return method ? !isImageMethodDisabled(method) : false
    })

    // 普通用户必须显式限定为基础方式：
    // 后端约定「enabledImageMethods 为空即支持全部方式」，什么都不传会把会员专属方式也放进去
    let enabledImageMethods: string[] | undefined =
      usableMethods.length > 0 ? usableMethods : undefined
    if (!canUsePremiumImages.value && usableMethods.length === 0) {
      enabledImageMethods = BASIC_IMAGE_METHOD_VALUES
    }

    // 创建任务，返回 BaseResponse<String>，taskId 在 data.data 中
    // style 为空时传 undefined，后端按「默认风格」处理
    const res = await createArticle({
      topic: trimmed,
      style: selectedStyle.value || undefined,
      enabledImageMethods,
    })
    const newTaskId = res.data?.data
    if (!newTaskId) {
      throw new Error(res.data?.message || '创建任务失败')
    }
    taskId.value = newTaskId

    // 把 taskId 写进地址栏：刷新页面时 onMounted 可据此恢复创作现场，
    // 否则刷新即回到输入页、找不到刚才的任务
    void router.replace({ query: { ...route.query, taskId: newTaskId } })

    // 拿到 taskId 后立即建连：后端不缓存历史消息，越早连接越不容易丢首条进度
    createProgressSource(newTaskId)
  } catch (error: any) {
    message.error(error?.message || '创建任务失败')
    isCreating.value = false
    currentPhase.value = 'INPUT'
  }
}

/**
 * 确认标题，进入大纲生成
 *
 * 成功后会乐观切到「大纲生成中」：上一阶段结束时服务端已主动关闭连接，
 * 如果推送因网络问题丢失，用户也不会停在「选择标题」界面以为确认没生效。
 * 大纲数据仍以 SSE 推送为准。
 */
const handleConfirmTitle = async (data: {
  mainTitle: string
  subTitle: string
  userDescription: string
}) => {
  confirmLoading.value = true
  try {
    await confirmTitle({
      taskId: taskId.value,
      selectedMainTitle: data.mainTitle,
      selectedSubTitle: data.subTitle,
      userDescription: data.userDescription,
    })

    // 先落到本地状态，供「大纲生成中」阶段展示标题
    article.value.mainTitle = data.mainTitle
    article.value.subTitle = data.subTitle

    currentPhase.value = 'OUTLINE_GENERATING'
    isCreating.value = true

    // 必须「先推进阶段、后建连」：
    // 若先建连，getProgress 读到的 phase 仍是 TITLE_SELECTING，
    // 会补发一条 TITLES_GENERATED，前端刚收到就 close，反而收不到阶段 2 的推送
    createProgressSource(taskId.value)

    message.success('标题已确认，正在生成大纲...')
  } catch (error: any) {
    message.error(error?.message || '确认标题失败')
  } finally {
    confirmLoading.value = false
  }
}

/** 确认大纲，进入正文生成 */
const handleConfirmOutline = async (
  outlineData: Array<{ section: number; title: string; points: string[] }>,
) => {
  confirmLoading.value = true
  try {
    await confirmOutline({
      taskId: taskId.value,
      outline: outlineData,
    })

    // 同步为大纲原文，便于后续展示与导出
    outlineRaw.value = JSON.stringify({ sections: outlineData })
    // 以用户编辑后的版本为准，否则完成态导出用的还是 AI 最初生成的大纲
    article.value.outline = outlineData

    currentPhase.value = 'CONTENT_GENERATING'
    isCreating.value = true

    // 同「确认标题」：先推进阶段再建连，避免补发旧阶段快照导致连接被立即关闭
    createProgressSource(taskId.value)

    message.success('大纲已确认，正在生成正文...')
  } catch (error: any) {
    message.error(error?.message || '确认大纲失败')
  } finally {
    confirmLoading.value = false
  }
}

/** 输入框按下回车：仅 Ctrl/Cmd + Enter 触发，避免误提交 */
const handlePressEnter = (event: KeyboardEvent) => {
  if (event.ctrlKey || event.metaKey) {
    void startCreate()
  }
}

/** 失败后重试 */
const retryCreate = () => {
  errorVisible.value = false
  void startCreate()
}

// ==================== 结果操作 ====================

/** 复制全文 */
const copyContent = async () => {
  const text = article.value.fullContent || article.value.content || ''
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
const exportCurrent = () => {
  const markdown = buildArticleMarkdown(article.value)
  if (!markdown) {
    message.warning('暂无可导出的内容')
    return
  }
  downloadMarkdown(markdown, article.value.mainTitle || topic.value)
  message.success('已导出 Markdown')
}

/** 查看详情 */
const viewArticle = () => {
  if (!taskId.value) {
    message.warning('任务 ID 不存在')
    return
  }
  router.push(`/article/${taskId.value}`)
}

/** 重新创作 */
const resetCreate = () => {
  sseConnection?.close()
  sseConnection = null
  topic.value = ''
  // 一并重置创作参数，避免下一篇沿用上一篇的风格与配图方式
  selectedStyle.value = ''
  selectedImageMethods.value = []
  // 重置阶段与阶段数据，否则「再创作一篇」会残留上一次的标题方案与大纲
  currentPhase.value = 'INPUT'
  titleOptions.value = []
  outline.value = []
  confirmLoading.value = false
  taskId.value = ''
  // 同步清掉地址栏里的 taskId，否则刷新后恢复逻辑又会被拉回上一个任务
  const nextQuery = { ...route.query }
  delete nextQuery.taskId
  void router.replace({ query: nextQuery })
  currentStep.value = 0
  isCreating.value = false
  isCompleted.value = false
  autoFollow.value = true
  resetStreamingState()
}

/** 点击热门选题填入输入框 */
const applyTopic = (value: string) => {
  topic.value = value
}

// ==================== 生命周期 ====================

onMounted(() => {
  // 支持从列表页「重新创作」跳转时带上选题
  const queryTopic = route.query.topic
  if (typeof queryTopic === 'string' && queryTopic.trim()) {
    topic.value = queryTopic.trim()
  }
  // 支持从地址栏恢复：刷新页面、或从列表页「继续编辑」带 taskId 进入
  const resumeTaskId = route.query.taskId
  if (typeof resumeTaskId === 'string' && resumeTaskId.trim()) {
    void resumeFromTaskId(resumeTaskId.trim())
  }
})

onBeforeUnmount(() => {
  if (scrollFrame) {
    cancelAnimationFrame(scrollFrame)
    scrollFrame = 0
  }
  sseConnection?.close()
  sseConnection = null
})
</script>

<style scoped lang="scss">
$primary: #667eea;
$primary-dark: #764ba2;

.article-create-page {
  min-height: 100vh;
  padding: 24px;
  background: #f0f2f5;
  box-sizing: border-box;
}

/* 三栏布局：左侧流程 280 / 中间自适应 / 右侧辅助 280 */
.create-layout {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr) 280px;
  gap: 20px;
  max-width: 1600px;
  margin: 0 auto;
  align-items: start;
}

/* ==================== 左侧 ==================== */
.sidebar-left {
  position: sticky;
  top: 24px;
  padding: 20px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);

  .sidebar-header {
    margin-bottom: 20px;

    .sidebar-title {
      margin: 0 0 4px;
      font-size: 16px;
      font-weight: 600;
      color: #1a1a1a;
    }

    .sidebar-subtitle {
      margin: 0;
      font-size: 12px;
      color: #999;
    }
  }
}

.flow-timeline {
  margin: 0;
  padding: 0;
  list-style: none;
}

.flow-item {
  position: relative;
  display: flex;
  gap: 12px;
  padding-bottom: 24px;

  /* 竖直连接线 */
  &::before {
    content: '';
    position: absolute;
    left: 15px;
    top: 32px;
    bottom: 0;
    width: 2px;
    background: #e8e8e8;
  }

  &:last-child {
    padding-bottom: 0;

    &::before {
      display: none;
    }
  }

  .flow-indicator {
    position: relative;
    z-index: 1;
    flex: 0 0 32px;
    width: 32px;
    height: 32px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 50%;
    background: #f0f2f5;
    color: #999;
    font-size: 14px;
    transition: all 0.3s;
  }

  .flow-content {
    flex: 1;
    min-width: 0;
    padding-top: 2px;
  }

  .flow-title {
    font-size: 14px;
    font-weight: 600;
    color: #666;
    transition: color 0.3s;
  }

  .flow-desc {
    margin-top: 2px;
    font-size: 12px;
    line-height: 1.5;
    color: #bfbfbf;
  }

  .flow-status {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-top: 6px;
    font-size: 12px;
    color: $primary;
  }

  .status-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: $primary;
    animation: pulse 1.2s ease-in-out infinite;
  }

  /* 已完成 */
  &.completed {
    .flow-indicator {
      background: #f6ffed;
      color: #52c41a;
    }

    .flow-title {
      color: #1a1a1a;
    }
  }

  /* 执行中 */
  &.active {
    .flow-indicator {
      background: linear-gradient(135deg, $primary 0%, $primary-dark 100%);
      color: #fff;
      box-shadow: 0 0 0 4px rgba(102, 126, 234, 0.15);
    }

    .flow-title {
      color: $primary;
    }

    .flow-desc {
      color: #8c8c8c;
    }
  }
}

.spin-icon {
  animation: spin 1s linear infinite;
}

/* ==================== 中间 ==================== */
.main-content {
  min-width: 0;
  max-height: calc(100vh - 48px);
  overflow-y: auto;
  padding-right: 4px;

  &::-webkit-scrollbar {
    width: 6px;
  }

  &::-webkit-scrollbar-thumb {
    background: #d9d9d9;
    border-radius: 3px;
  }
}

/* 输入状态 */
.input-card {
  padding: 40px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);

  .input-header {
    margin-bottom: 28px;
    text-align: center;
  }

  .input-title {
    margin: 0 0 8px;
    font-size: 28px;
    font-weight: 700;
    color: #1a1a1a;
  }

  .input-subtitle {
    margin: 0;
    font-size: 15px;
    color: #8c8c8c;
  }

  .input-area {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .topic-textarea {
    font-size: 15px;
    border-radius: 8px;
  }

  .create-btn {
    height: 52px;
    font-size: 17px;
    font-weight: 600;
    border: none;
    border-radius: 8px;
    background: linear-gradient(135deg, $primary 0%, $primary-dark 100%);
    transition: transform 0.2s, box-shadow 0.2s;

    &:not(:disabled):hover {
      transform: translateY(-2px);
      box-shadow: 0 8px 16px rgba(102, 126, 234, 0.35);
    }
  }

  .input-hint {
    margin: 0;
    font-size: 12px;
    color: #bfbfbf;
    text-align: center;
  }

  .quota-warning {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 14px;
    font-size: 13px;
    color: #d46b08;
    background: #fff7e6;
    border: 1px solid #ffd591;
    border-radius: 8px;
  }
}

/* 创作参数：文章风格 / 配图方式 */
.style-section,
.image-methods-section {
  padding: 16px;
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 10px;

  .section-header {
    display: flex;
    align-items: baseline;
    gap: 6px;
    margin-bottom: 12px;
  }

  .section-title {
    font-size: 14px;
    font-weight: 600;
    color: #1a1a1a;
  }

  .section-tip {
    font-size: 12px;
    color: #bfbfbf;
  }
}

/* 风格单选：把选项做成可点的卡片 */
.style-group {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;

  :deep(.ant-radio-wrapper) {
    margin: 0;
    padding: 6px 14px;
    background: #fff;
    border: 1px solid #e8e8e8;
    border-radius: 8px;
    transition: all 0.2s;

    &:hover {
      border-color: $primary;
      background: rgba(102, 126, 234, 0.04);
    }

    &.ant-radio-wrapper-checked {
      border-color: $primary;
      background: rgba(102, 126, 234, 0.08);
    }
  }
}

/* 配图方式多选：同样的卡片式外观 */
.methods-group {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;

  :deep(.ant-checkbox-wrapper) {
    margin: 0;
    padding: 6px 14px;
    background: #fff;
    border: 1px solid #e8e8e8;
    border-radius: 8px;
    transition: all 0.2s;

    &:hover {
      border-color: $primary;
      background: rgba(102, 126, 234, 0.04);
    }

    &.ant-checkbox-wrapper-checked {
      border-color: $primary;
      background: rgba(102, 126, 234, 0.08);
    }

    /* 禁用态（会员专属方式对普通用户不可选） */
    &.ant-checkbox-wrapper-disabled {
      background: #fafafa;
      opacity: 0.75;
      cursor: not-allowed;
    }
  }

  /* 会员专属标识 */
  .vip-method-mark {
    display: inline-block;
    margin-left: 4px;
    padding: 0 5px;
    font-size: 11px;
    font-weight: 600;
    line-height: 16px;
    color: #ad6800;
    background: #fff7e6;
    border: 1px solid #ffd591;
    border-radius: 4px;
    vertical-align: middle;
  }
}

/* 阶段切换过渡：离开向左淡出、进入从右滑入，营造「往前走」的流程感 */
.fade-slide-enter-active,
.fade-slide-leave-active {
  transition: all 0.3s ease;
}

.fade-slide-enter-from {
  opacity: 0;
  transform: translateX(30px);
}

.fade-slide-leave-to {
  opacity: 0;
  transform: translateX(-30px);
}

/* 生成中的加载态 */
.loading-stage {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 120px 40px;
  text-align: center;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);

  h3 {
    margin: 24px 0 8px;
    font-size: 20px;
    font-weight: 600;
    color: #1a1a1a;
  }

  p {
    margin: 0;
    font-size: 14px;
    color: #8c8c8c;
  }
}

/* 大纲生成中：标题预览 + 流式大纲 */
.outline-generating-state {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* 创作中状态 */
.creating-state {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.preview-header,
.outline-preview,
.content-preview,
.image-progress-box,
.loading-placeholder {
  padding: 24px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.preview-header {
  background: linear-gradient(135deg, #f5f7fa 0%, #e4e9f2 100%);

  .article-title {
    margin: 0 0 10px;
    font-size: 26px;
    font-weight: 700;
    line-height: 1.4;
    color: #1a1a1a;
  }

  .article-subtitle {
    margin: 0;
    font-size: 15px;
    color: #595959;
  }
}

.section-label {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  font-size: 15px;
  font-weight: 600;
  color: $primary;
}

.outline-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.outline-item {
  padding: 14px 16px;
  background: #fafafa;
  border-left: 3px solid $primary;
  border-radius: 6px;

  .outline-title {
    font-size: 15px;
    font-weight: 600;
    color: #1a1a1a;
  }

  .outline-points {
    margin: 8px 0 0;
    padding-left: 20px;

    li {
      margin-bottom: 4px;
      font-size: 13px;
      line-height: 1.7;
      color: #595959;

      &:last-child {
        margin-bottom: 0;
      }
    }
  }
}

.image-progress-box {
  .progress-header {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 12px;
    font-size: 15px;
    font-weight: 600;
    color: #16a34a;
  }

  .progress-hint {
    margin: 8px 0 0;
    font-size: 13px;
    color: #8c8c8c;
  }
}

.loading-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 60px 24px;
  color: #8c8c8c;
}

/* 打字机光标 */
.typing-cursor {
  display: inline-block;
  margin-left: 2px;
  font-weight: 400;
  color: $primary;
  animation: blink 1s step-end infinite;
}

/* 完成状态 */
.completed-state {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.success-banner {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px 24px;
  background: #f6ffed;
  border: 1px solid #b7eb8f;
  border-radius: 12px;

  .success-icon {
    font-size: 32px;
    color: #52c41a;
  }

  .success-text {
    h2 {
      margin: 0 0 4px;
      font-size: 18px;
      font-weight: 600;
      color: #1a1a1a;
    }

    p {
      margin: 0;
      font-size: 13px;
      color: #8c8c8c;
    }
  }
}

.completed-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.completed-article {
  padding: 40px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);

  .final-title {
    margin: 0 0 12px;
    font-size: 30px;
    font-weight: 700;
    line-height: 1.4;
    color: #1a1a1a;
  }

  .final-subtitle {
    margin: 0 0 8px;
    font-size: 16px;
    color: #8c8c8c;
  }
}

/* 完成态：没有图文合成结果时单独展示的配图 */
.completed-images {
  margin-top: 24px;

  .section-label {
    margin-bottom: 12px;
  }

  .images-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
    gap: 12px;
  }

  .image-item {
    margin: 0;
    overflow: hidden;
    background: #fafafa;
    border: 1px solid #f0f0f0;
    border-radius: 10px;

    img {
      display: block;
      width: 100%;
      height: 150px;
      object-fit: cover;
      background: #f0f0f0;
    }

    .image-info {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      padding: 8px 10px;

      .keywords {
        overflow: hidden;
        font-size: 12px;
        color: #999;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }
  }
}

/* markdown 渲染结果（v-html 插入，需要 :deep 穿透 scoped） */
.markdown-body {
  font-size: 16px;
  line-height: 1.9;
  color: #333;
  word-break: break-word;

  :deep(h1),
  :deep(h2),
  :deep(h3),
  :deep(h4) {
    margin: 28px 0 14px;
    font-weight: 600;
    line-height: 1.4;
    color: #1a1a1a;
  }

  :deep(h1) {
    font-size: 26px;
  }

  :deep(h2) {
    padding-bottom: 8px;
    font-size: 22px;
    border-bottom: 2px solid #f0f0f0;
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
    color: #595959;
    background: #fafafa;
    border-left: 4px solid #d9d9d9;
  }

  :deep(code) {
    padding: 2px 6px;
    font-family: Consolas, Monaco, monospace;
    font-size: 0.9em;
    background: #f5f5f5;
    border-radius: 4px;
  }

  :deep(pre) {
    margin: 0 0 16px;
    padding: 16px;
    overflow-x: auto;
    background: #f5f5f5;
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
      border: 1px solid #e8e8e8;
    }

    th {
      background: #fafafa;
      font-weight: 600;
    }
  }
}

/* ==================== 右侧 ==================== */
.sidebar-right {
  position: sticky;
  top: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.panel-card {
  padding: 20px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  text-align: center;

  .panel-title {
    display: flex;
    align-items: center;
    gap: 6px;
    margin: 0 0 16px;
    font-size: 15px;
    font-weight: 600;
    text-align: left;
    color: #1a1a1a;
  }
}

.topic-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;

  .topic-chip {
    width: 100%;
    padding: 9px 12px;
    font-size: 13px;
    color: #595959;
    text-align: left;
    background: #fafafa;
    border: 1px solid #f0f0f0;
    border-radius: 8px;
    cursor: pointer;
    transition: all 0.2s;

    &:not(:disabled):hover {
      color: $primary;
      background: #f0f2ff;
      border-color: $primary;
    }

    &:disabled {
      cursor: not-allowed;
      opacity: 0.6;
    }

    &:focus-visible {
      outline: 2px solid $primary;
      outline-offset: 1px;
    }
  }
}

.task-meta {
  margin: 16px 0 0;

  .meta-row {
    display: flex;
    justify-content: space-between;
    gap: 8px;
    padding: 6px 0;
    font-size: 13px;
    border-bottom: 1px dashed #f0f0f0;

    &:last-child {
      border-bottom: none;
    }

    dt {
      flex: 0 0 auto;
      color: #8c8c8c;
    }

    dd {
      flex: 1;
      min-width: 0;
      margin: 0;
      overflow: hidden;
      color: #1a1a1a;
      text-align: right;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }
}

/* 完成后统计 */
.stats-section {
  .stats-grid {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 12px;
  }

  .stat-item {
    padding: 12px 8px;
    background: #fafafa;
    border-radius: 8px;
  }

  .stat-value {
    font-size: 20px;
    font-weight: 700;
    color: $primary;
  }

  .stat-label {
    margin-top: 2px;
    font-size: 12px;
    color: #8c8c8c;
  }
}

.error-message {
  margin-bottom: 20px;
  color: #595959;
}

.error-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

/* ==================== 动画 ==================== */
@keyframes blink {
  0%,
  50% {
    opacity: 1;
  }

  51%,
  100% {
    opacity: 0;
  }
}

@keyframes pulse {
  0%,
  100% {
    opacity: 1;
    transform: scale(1);
  }

  50% {
    opacity: 0.4;
    transform: scale(1.3);
  }
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* ==================== 响应式 ==================== */
/* 中等屏：收起右侧辅助栏 */
@media (max-width: 1280px) {
  .create-layout {
    grid-template-columns: 240px minmax(0, 1fr);
  }

  .sidebar-right {
    display: none;
  }
}

/* 窄屏：单列堆叠，流程改为横向 */
@media (max-width: 900px) {
  .article-create-page {
    padding: 12px;
  }

  .create-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .sidebar-left {
    position: static;
  }

  .flow-timeline {
    display: flex;
    gap: 16px;
    overflow-x: auto;
    padding-bottom: 4px;
  }

  .flow-item {
    flex: 0 0 auto;
    padding-bottom: 0;

    &::before {
      display: none;
    }

    .flow-desc {
      display: none;
    }
  }

  .main-content {
    max-height: none;
    overflow-y: visible;
  }

  .completed-article,
  .input-card {
    padding: 20px;
  }
}
</style>
