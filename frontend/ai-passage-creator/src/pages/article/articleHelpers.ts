/**
 * 文章模块公共工具函数
 *
 * 只服务于 src/pages/article 下的页面，抽离三个页面重复需要的逻辑：
 * 1）后端状态枚举 → 中文文案 / 组件配色
 * 2）后端时间格式化（LocalDateTime 的两种序列化形态都要能兼容）
 * 3）文章 Markdown 组装与下载（创作页「导出」与详情页「导出」共用同一份规则）
 * 4）剪贴板复制（带非安全上下文的降级方案）
 * 5）文章风格与配图方式的选项定义、配图来源文案
 *
 * @author ai-passage-creator
 */
import dayjs, { type Dayjs } from 'dayjs'

/** 文章风格选项，value 对应后端 ArticleStyleEnum；空值表示使用默认风格 */
export interface ArticleStyleOption {
  value: string
  label: string
}

export const ARTICLE_STYLE_OPTIONS: ArticleStyleOption[] = [
  { value: 'tech', label: '科技风格' },
  { value: 'emotional', label: '情感风格' },
  { value: 'educational', label: '教育风格' },
  { value: 'humorous', label: '轻松幽默' },
]

/** 配图方式选项，value 对应后端 ImageMethodEnum */
export interface ImageMethodOption {
  value: string
  label: string
  description: string
}

/**
 * 可选的配图方式
 *
 * 后端枚举里还有 PEXELS，但项目没有接入该图库（默认检索方式已改为 UNSPLASH），
 * 所以不暴露给用户——否则选了它也拿不到图，只会白白降级成 Picsum 随机图。
 * PICSUM 是后端兜底方案，同样不作为可选项。
 */
export const IMAGE_METHOD_OPTIONS: ImageMethodOption[] = [
  { value: 'UNSPLASH', label: 'Unsplash', description: '真实场景、人物照片、自然风景' },
  { value: 'NANO_BANANA', label: 'Nano Banana', description: '创意插画、艺术风格、文字渲染' },
  { value: 'MERMAID', label: 'Mermaid', description: '流程图、架构图、时序图' },
  { value: 'ICONIFY', label: 'Iconify', description: '图标、符号、小型装饰' },
  { value: 'EMOJI_PACK', label: '表情包', description: '搞笑、轻松幽默的配图' },
  { value: 'SVG_DIAGRAM', label: 'SVG 示意图', description: '概念示意、逻辑关系展示' },
]

/** 配图来源值 → 中文名（含未开放选择、但可能出现在历史数据里的降级方案） */
const IMAGE_METHOD_TEXT: Record<string, string> = {
  UNSPLASH: 'Unsplash 图库',
  PEXELS: 'Pexels 图库',
  NANO_BANANA: 'Nano Banana 生图',
  MERMAID: 'Mermaid 图表',
  ICONIFY: 'Iconify 图标',
  EMOJI_PACK: '表情包',
  SVG_DIAGRAM: 'SVG 示意图',
  PICSUM: '随机图（降级）',
}

/** 配图来源值转中文文案 */
export const getImageMethodText = (method?: string): string =>
  method ? IMAGE_METHOD_TEXT[method] ?? method : '未知来源'

/** 后端文章状态枚举，对应 ArticleStatusEnum */
export type ArticleStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED'

const STATUS_TEXT: Record<string, string> = {
  PENDING: '等待中',
  PROCESSING: '生成中',
  COMPLETED: '已完成',
  FAILED: '失败',
}

/** a-tag / a-badge / a-progress 可识别的状态色 */
const STATUS_COLOR: Record<string, string> = {
  PENDING: 'default',
  PROCESSING: 'processing',
  COMPLETED: 'success',
  FAILED: 'error',
}

/** 状态值转中文文案 */
export const getStatusText = (status?: string): string => (status ? STATUS_TEXT[status] ?? status : '未知')

/** 状态值转组件配色 */
export const getStatusColor = (status?: string): string => (status ? STATUS_COLOR[status] ?? 'default' : 'default')

/**
 * 把后端时间字段解析成 dayjs 对象
 *
 * Spring 的 LocalDateTime 有两个序列化形态，这里都要兜住：
 * - 配置了 JavaTimeModule：'2026-03-10T17:26:00'
 * - 未配置（Jackson 默认）：[2026, 3, 10, 17, 26, 0]
 */
export const parseDateTime = (value?: string | number[] | null): Dayjs | null => {
  if (!value) {
    return null
  }

  if (Array.isArray(value)) {
    const [year, month, day, hour, minute, second] = value
    if (year == null) {
      return null
    }
    // 注意：Date 的月份从 0 开始，因此减 1
    const parsed = dayjs(new Date(year, (month ?? 1) - 1, day ?? 1, hour ?? 0, minute ?? 0, second ?? 0))
    return parsed.isValid() ? parsed : null
  }

  const parsed = dayjs(value)
  return parsed.isValid() ? parsed : null
}

/** 格式化后端时间字段，无法解析时返回占位符 */
export const formatDateTime = (
  value?: string | number[] | null,
  template = 'YYYY-MM-DD HH:mm',
): string => {
  const parsed = parseDateTime(value)
  return parsed ? parsed.format(template) : '-'
}

/** 导出 Markdown 所需的最小字段集合 */
export interface MarkdownSource {
  mainTitle?: string
  subTitle?: string
  outline?: API.OutlineItem[]
  content?: string
  /** 图文合成结果，仅创作页的 SSE 消息里能拿到 */
  fullContent?: string
  images?: API.ImageItem[]
}

/**
 * 组装可导出的完整 Markdown 文档
 *
 * 正文优先取图文合成结果 fullContent，没有再退回纯文本 content。
 */
export const buildArticleMarkdown = (article: MarkdownSource): string => {
  const lines: string[] = []

  if (article.mainTitle) {
    lines.push(`# ${article.mainTitle}`, '')
  }
  if (article.subTitle) {
    lines.push(`> ${article.subTitle}`, '')
  }

  if (article.outline?.length) {
    lines.push('## 目录', '')
    article.outline.forEach((item, index) => {
      lines.push(`${item.section ?? index + 1}. ${item.title ?? ''}`)
      item.points?.forEach((point) => lines.push(`   - ${point}`))
    })
    lines.push('', '---', '')
  }

  const fullContent = article.fullContent?.trim()
  const body = fullContent || article.content?.trim()
  if (body) {
    lines.push(body, '')
  }

  // fullContent 是图文合成结果，插图已经内联在正文里，再拼一次「配图」会重复
  if (!fullContent && article.images?.length) {
    lines.push('## 配图', '')
    article.images.forEach((image, index) => {
      lines.push(`![${image.description || `配图${index + 1}`}](${image.url ?? ''})`, '')
    })
  }

  // 收敛多余空行，保证导出结果干净
  return lines.join('\n').replace(/\n{3,}/g, '\n\n').trim()
}

/** 按标题生成安全的下载文件名并触发下载 */
export const downloadMarkdown = (content: string, filename: string): void => {
  // 去掉 Windows / macOS 文件名非法字符，并限制长度
  const safeName = (filename || '文章').replace(/[\\/:*?"<>|]/g, '_').trim().slice(0, 60) || '文章'

  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')

  anchor.href = url
  anchor.download = `${safeName}.md`
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)
  URL.revokeObjectURL(url)
}

/**
 * 复制文本到剪贴板
 *
 * navigator.clipboard 只在安全上下文（https / localhost）可用，
 * 因此保留一份 textarea + execCommand 的降级实现。
 */
export const copyText = async (text: string): Promise<void> => {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text)
    return
  }

  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.top = '-9999px'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.select()
  document.execCommand('copy')
  document.body.removeChild(textarea)
}
